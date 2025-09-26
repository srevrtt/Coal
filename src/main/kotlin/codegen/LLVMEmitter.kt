package codegen

import ast.*
import codegen.ir.BlockBuilder
import codegen.ir.ModuleBuilder

class LLVMEmitter {
    private sealed interface RValue {
        data class Immediate(val llTy: String, val text: String) : RValue
        data class ValueReg(val llTy: String, val reg: String) : RValue
        data class Aggregate(val literal: String) : RValue
    }

    private data class LocalSlot(val llTy: String, val reg: String)

    private lateinit var mod: ModuleBuilder
    private val locals = LinkedHashMap<String, LocalSlot>()
    private var currentFn = ""

    private var labelCounter = 0

    fun emit(prog: Program): String {
        mod = ModuleBuilder()
        mod.declarePrintf()
        mod.declareSnprintf()
        mod.declareMalloc()
        mod.declareMemcpy()

        for(d in prog.decls) {
            when(d) {
                is FnDecl -> emitFn(d)
            }
        }

        return mod.toString()
    }

    private fun emitFn(fn: FnDecl) {
        currentFn = fn.name
        locals.clear()

        val fb = mod.function(fn.name, retTy = "i32").start()
        val b = fb.block("entry")

        for(s in fn.body.stmts) {
            when(s) {
                is VarDecl -> lowerVarDecl(b, fn.name, s)
                is Assign -> lowerAssign(b, fn.name, s)
                is ExprStmt -> valueOfExpr(b, s.expr)
                is IfStmt -> lowerIf(b, s)
                is WhileStmt -> lowerWhile(b, s)
            }
        }

        b.ret("i32", "0")
        fb.end()
    }

    private fun lowerVarDecl(b: BlockBuilder, fnName: String, decl: VarDecl) {
        val tyName = (decl.annotatedType ?: inferType(b, decl.init!!)) as NamedType
        val llTy = llTypeOf(tyName)
        val slotPtr = b.alloca(llTy, decl.name)

        if(decl.init != null) {
            val rhs = valueOfExpr(b, decl.init, llTy)
            when(rhs) {
                is RValue.Immediate -> b.store(rhs.llTy, rhs.text, slotPtr)
                is RValue.ValueReg -> b.store(rhs.llTy, rhs.reg, slotPtr)
                is RValue.Aggregate -> b.store(llTy, rhs.literal, slotPtr)
            }
        } else {
            b.store(llTy, zeroInit(llTy), slotPtr)
        }

        locals[decl.name] = LocalSlot(llTy, slotPtr)
        val t = b.load(llTy, slotPtr)
        val dbgName = "__dbg_${fnName}_${decl.name}"

        mod.global(dbgName, llTy, zeroInit(llTy))
        b.store(llTy, t, "@$dbgName")
    }

    private fun lowerAssign(b: BlockBuilder, fnName: String, asg: Assign) {
        val slot = locals[asg.name] ?: error("Undefined variable ${asg.name}")
        val rhs = valueOfExpr(b, asg.value, slot.llTy)
        when(rhs) {
            is RValue.Immediate -> b.store(rhs.llTy, rhs.text, slot.reg)
            is RValue.ValueReg -> b.store(rhs.llTy, rhs.reg, slot.reg)
            is RValue.Aggregate -> b.store(slot.llTy, rhs.literal, slot.reg)
        }

        val t = b.load(slot.llTy, slot.reg)
        val dbgName = "__dbg_${fnName}_${asg.name}"
        mod.global(dbgName, slot.llTy, zeroInit(slot.llTy))
        b.store(slot.llTy, t, "@$dbgName")
    }

    private fun valueOfExpr(b: BlockBuilder, e: Expr, expected: String? = null): RValue = when(e) {
        is IntLit -> RValue.Immediate("i32", e.value.toString())
        is FloatLit -> RValue.Immediate("double", e.value.toString())
        is BoolLit -> RValue.Immediate("i1", if(e.value) "1" else "0")
        is CharLit -> RValue.Immediate("i8", e.value.toString())
        is StringLit -> {
            val ref = mod.internCString(e.value)
            val len = utf8Len(e.value)
            RValue.Aggregate("{ ptr ${ref.constGEP}, i32 $len }")
        }

        is Ident -> {
            val slot = locals[e.name] ?: error("Undefined variable ${e.name}")
            val t = b.load(slot.llTy, slot.reg)
            RValue.ValueReg(slot.llTy, t)
        }

        is Binary -> lowerBinary(b, e)
        is Call -> when(e.callee) {
            "print" -> lowerBuiltinPrint(b, e.args, false)
            "println" -> lowerBuiltinPrint(b, e.args, true)
            else -> error("Unknown function ${e.callee}")
        }

        is Unary -> when(e.op) {
            UnOp.Not -> {
                val v = valueOfExpr(b, e.expr)
                val (ty, op) = asOperand(v)
                require(ty == "i1") { "not operator requires boolean operand" }
                val t = b.xorI1(op)
                RValue.ValueReg("i1", t)
            }
        }

        is MethodCall -> lowerMethodCall(b, e)
    }

    private fun lowerIf(b: BlockBuilder, s: IfStmt) {
        val end = fresh("if_end")
        val thenLabels = s.branches.indices.map { fresh("if_then$it") }
        val checkLabels = s.branches.indices.drop(1).map { fresh("if_chk$it") }
        val elseLabel = s.elseBranch?.let { fresh("if_else") }

        fun condTo(bld: BlockBuilder, cond: Expr, thenLabel: String, elseLabel: String) {
            val rv = valueOfExpr(bld, cond)
            val (ty, op) = asOperand(rv)
            require(ty == "i1") { "if condition must be boolean, got $ty" }
            bld.brCond("i1", op, thenLabel, elseLabel)
        }

        val nextAfterFirst = checkLabels.firstOrNull() ?: elseLabel ?: end
        condTo(b, s.branches.first().condition, thenLabels.first(), nextAfterFirst)

        s.branches.drop(1).forEachIndexed { idx, br ->
            val cb = b.nextBlock(checkLabels[idx])
            val elseTgt = if(idx == s.branches.size - 2) (elseLabel ?: end) else checkLabels[idx + 1]
            condTo(cb, br.condition, thenLabels[idx + 1], elseTgt)
        }

        s.branches.forEachIndexed { i, br ->
            val tb = b.nextBlock(thenLabels[i])
            br.body.stmts.forEach { st ->
                when(st) {
                    is VarDecl -> lowerVarDecl(tb, currentFn, st)
                    is Assign -> lowerAssign(tb, currentFn, st)
                    is ExprStmt -> valueOfExpr(tb, st.expr)
                    is IfStmt -> lowerIf(tb, st)
                    is WhileStmt -> lowerWhile(tb, st)
                }
            }

            tb.br(end)
        }

        if(s.elseBranch != null) {
            val eb = b.nextBlock(elseLabel!!)
            s.elseBranch.stmts.forEach { st ->
                when(st) {
                    is VarDecl -> lowerVarDecl(eb, currentFn, st)
                    is Assign -> lowerAssign(eb, currentFn, st)
                    is ExprStmt -> valueOfExpr(eb, st.expr)
                    is IfStmt -> lowerIf(eb, st)
                    is WhileStmt -> lowerWhile(eb, st)
                }
            }

            eb.br(end)
        }

        b.nextBlock(end)
    }

    private fun lowerWhile(b: BlockBuilder, s: WhileStmt) {
        // TODO:

    }

    private fun lowerMethodCall(b: BlockBuilder, m: MethodCall): RValue {
        require(m.args.isEmpty()) { "conversion methods take no arguments" }
        val recv = valueOfExpr(b, m.receiver)

        return when(m.method) {
            "toString" -> when(recv) {
                is RValue.Aggregate -> recv
                is RValue.Immediate, is RValue.ValueReg -> numberOrCharToString(b, recv)
            }

            "toInt" -> when (recv) {
                is RValue.Aggregate, is RValue.Immediate, is RValue.ValueReg -> {
                    val receiverLlType = when (recv) {
                        is RValue.Immediate -> recv.llTy
                        is RValue.ValueReg -> recv.llTy
                        else -> ""
                    }
                    if (receiverLlType == "double") {
                        // Convert float to int
                        val intRegister = b.fptosi("double", asOperand(recv).second, "i32")
                        RValue.ValueReg("i32", intRegister)
                    } else if (receiverLlType == "{ ptr, i32 }") {
                        // Convert string literal to int
                        stringToIntIfLiteral(m.receiver)
                    } else {
                        error("toInt() not supported for type $receiverLlType")
                    }
                }
            }

            "toFloat" -> when (recv) {
                is RValue.Immediate, is RValue.ValueReg -> {
                    val receiverLlType = when (recv) {
                        is RValue.Immediate -> recv.llTy
                        is RValue.ValueReg -> recv.llTy
                        else -> ""
                    }
                    if (receiverLlType == "i32") {
                        // Convert int to float
                        val floatRegister = b.sitofp("i32", asOperand(recv).second, "double")
                        RValue.ValueReg("double", floatRegister)
                    } else if (receiverLlType == "{ ptr, i32 }") {
                        // Convert string literal to float
                        stringToFloatIfLiteral(m.receiver)
                    } else {
                        error("toFloat() not supported for type $receiverLlType")
                    }
                }
                else -> error("toFloat() supported only on int or string types")
            }
            else -> error("unknown method ${m.method}")
        }
    }

    private fun lowerBinary(b: BlockBuilder, bin: Binary): RValue {
        when(bin.op) {
            BinOp.And -> {
                val lhs = valueOfExpr(b, bin.left)
                val (lty, lop) = asOperand(lhs)
                require(lty == "i1") { "left operand of && must be boolean, got $lty" }

                val evalR = fresh("and_r")
                val falseBlk = fresh("and_false")
                val done = fresh("and_end")

                b.brCond("i1", lop, evalR, falseBlk)
                val tb = b.nextBlock(evalR)
                val rhs = valueOfExpr(tb, bin.right)
                val (rty, rop) = asOperand(rhs)
                require(rty == "i1") { "right operand of && must be boolean, got $rty" }
                tb.br(done)

                val fb = b.nextBlock(falseBlk)
                fb.br(done)

                val jb = b.nextBlock(done)
                val phi = jb.phi("i1", "0" to falseBlk, rop to evalR)
                return RValue.ValueReg("i1", phi)
            }

            BinOp.Or -> {
                val lhs = valueOfExpr(b, bin.left)
                val (lty, lop) = asOperand(lhs)
                require(lty == "i1") { "left operand of || must be boolean, got $lty" }

                val trueBlk = fresh("or_true")
                val evalR = fresh("or_r")
                val done = fresh("or_end")

                b.brCond("i1", lop, trueBlk, evalR)
                val tb = b.nextBlock(trueBlk)
                tb.br(done)

                val rb = b.nextBlock(evalR)
                val rhs = valueOfExpr(rb, bin.right)
                val (rty, rop) = asOperand(rhs)
                require(rty == "i1") { "right operand of || must be boolean, got $rty" }
                rb.br(done)

                val jb = b.nextBlock(done)
                val phi = jb.phi("i1", "1" to trueBlk, rop to evalR)
                return RValue.ValueReg("i1", phi)
            }

            else -> {}
        }

        when(bin.op) {
            BinOp.Eq, BinOp.Ne, BinOp.Lt, BinOp.Le, BinOp.Gt, BinOp.Ge -> {
                val lrv = valueOfExpr(b, bin.left)
                val rrv = valueOfExpr(b, bin.right)

                fun both(): Triple<String, String, String> {
                    val (lt, lo) = asOperand(lrv)
                    val (rt, ro) = asOperand(rrv)
                    require(lt == rt) { "type mismatch in comparison: $lt vs $rt" }
                    return Triple(lt, lo, ro)
                }

                val (llTy, lhs, rhs) = both()
                val out = when(llTy) {
                    "i32", "i8", "i1" -> {
                        val pred = when(bin.op) {
                            BinOp.Eq -> "eq"
                            BinOp.Ne -> "ne"
                            BinOp.Lt -> "slt"
                            BinOp.Le -> "sle"
                            BinOp.Gt -> "sgt"
                            BinOp.Ge -> "sge"
                            else -> error("unreachable")
                        }

                        b.icmp(pred, llTy, lhs, rhs)
                    }

                    "double" -> {
                        val pred = when(bin.op) {
                            BinOp.Eq -> "oeq"
                            BinOp.Ne -> "one"
                            BinOp.Lt -> "olt"
                            BinOp.Le -> "ole"
                            BinOp.Gt -> "ogt"
                            BinOp.Ge -> "oge"
                            else -> error("unreachable")
                        }

                        b.fcmp(pred, lhs, rhs)
                    }

                    "{ ptr, i32 }" -> {
                        require(bin.op == BinOp.Eq || bin.op == BinOp.Ne) {
                            "only == and != supported for strings"
                        }

                        val lp = extractStringPtr(b, lrv)
                        val rp = extractStringPtr(b, rrv)
                        val pred = if(bin.op == BinOp.Eq) "eq" else "ne"
                        b.icmp(pred, "ptr", lp, rp)
                    }

                    else -> error("unsupported type in comparison: $llTy")
                }

                return RValue.ValueReg("i1", out)
            }

            else -> {}
        }

        run {
            val lt = inferType(b, bin.left) as NamedType
            val rt = inferType(b, bin.right) as NamedType
            if(lt.name == "string" && rt.name == "string") {
                require(bin.op == BinOp.Add) { "only + supported for strings" }
                val lhs = valueOfExpr(b, bin.left)
                val rhs = valueOfExpr(b, bin.right)
                return concatStrings(b, lhs, rhs)
            }
        }

        val tyName = (inferType(b, bin) as NamedType).name
        val llTy = when(tyName) {
            "float" -> "double"
            "int" -> "i32"
            "char" -> "i8"
            else -> error("unsupported type in binary expression: $tyName")
        }

        fun asOp(rv: RValue): String = when(rv) {
            is RValue.Immediate -> rv.text
            is RValue.ValueReg -> rv.reg
            is RValue.Aggregate -> error("Cannot use aggregate in binary expression")
        }

        var lhs = valueOfExpr(b, bin.left, llTy)
        var rhs = valueOfExpr(b, bin.right, llTy)

        fun maybeZextToI32(v: RValue): RValue = when(v) {
            is RValue.ValueReg -> if(v.llTy == "i8") {
                val z = b.zext("i8", v.reg, "i32")
                RValue.ValueReg("i32", z)
            } else v

            is RValue.Immediate -> if(v.llTy == "i8") RValue.Immediate("i32", v.text) else v
            else -> v
        }

        val useFloat = (llTy == "double")
        if(!useFloat) {
            lhs = maybeZextToI32(lhs)
            rhs = maybeZextToI32(rhs)
        }

        val a = asOp(lhs)
        val c = asOp(rhs)
        val res = when(bin.op) {
            BinOp.Pow -> {
                if(useFloat) b.fpow(a, c)
                else b.pow(a, c)
            }

            BinOp.Add -> {
                if(useFloat) b.fadd(a, c)
                else b.add(if((lhs as? RValue.ValueReg)?.llTy == "i32" || (lhs as? RValue.Immediate)?.llTy == "i32") "i32" else "i32", a, c)
            }

            BinOp.Sub -> {
                if(useFloat) b.fsub(a, c)
                else b.sub("i32", a, c)
            }

            BinOp.Mul -> {
                if(useFloat) b.fmul(a, c)
                else b.mul("i32", a, c)
            }

            BinOp.Div -> {
                if(useFloat) b.fdiv(a, c)
                else b.sdiv("i32", a, c)
            }

            BinOp.Mod -> {
                require(!useFloat) { "modulus supported only for integers" }
                b.srem("i32", a, c)
            }

            else -> error("Unexpected binary operator in arithmetic: ${bin.op}")
        }

        return RValue.ValueReg(if(useFloat) "double" else "i32", res)
    }

    private fun lowerBuiltinPrint(b: BlockBuilder, args: List<Expr>, newline: Boolean): RValue {
        require(args.size == 1) { "print/println takes exactly one argument" }
        val rv = valueOfExpr(b, args[0])

        fun isStringAggReg(v: RValue) = v is RValue.ValueReg && v.llTy == "{ ptr, i32 }"
        if(rv is RValue.Aggregate || isStringAggReg(rv)) {
            val fmtRef = mod.internCString(if (newline) "%s\n" else "%s")
            val fmtPtr = b.gepGlobalFirst(fmtRef)

            val strPtr = when (rv) {
                is RValue.Aggregate -> b.extractValue("{ ptr, i32 }", rv.literal, 0)
                is RValue.ValueReg  -> b.extractValue("{ ptr, i32 }", rv.reg, 0)
                else -> error("unreachable")
            }

            b.callPrintf(fmtPtr, "ptr" to strPtr)
            return RValue.Immediate("i32", "0")
        }

        val (ty, op) = asOperand(rv)
        val fmt = when(ty) {
            "i32", "i1", "i8" -> if(newline) "%d\n" else "%d"
            "double" -> if(newline) "%f\n" else "%f"
            else -> error("unsupported print type: $ty")
        }

        val fmtG = mod.internCString(fmt)
        val argTy = when (ty) { "i1","i8" -> "i32"; else -> ty }
        b.callPrintf(fmtG.constGEP, argTy to op)

        return RValue.Immediate("i32", "0")
    }

    // utils
    private fun inferType(b: BlockBuilder, expr: Expr): TypeRef = when(expr) {
        is IntLit -> NamedType("int")
        is FloatLit -> NamedType("float")
        is BoolLit -> NamedType("bool")
        is CharLit -> NamedType("char")
        is StringLit -> NamedType("string")
        is Ident -> {
            val slot = locals[expr.name] ?: error("unknown ident in inferType: ${expr.name}")
            when(slot.llTy) {
                "i32" -> NamedType("int")
                "double" -> NamedType("float")
                "i1" -> NamedType("bool")
                "i8" -> NamedType("char")
                "{ ptr, i32 }" -> NamedType("string")
                else -> error("cannot infer from $slot")
            }
        }

        is Binary -> {
            val lt = inferType(b, expr.left) as NamedType
            val rt = inferType(b, expr.right) as NamedType
            require(lt == rt) { "type mismatch in binary expression: $lt vs $rt" }
            when(lt.name) {
                "int", "float" -> lt
                "string" -> lt
                else -> error("unsupported type in binary expression: ${lt.name}")
            }
        }

        is Call -> NamedType("int")
        is MethodCall -> when(expr.method) {
            "toString" -> NamedType("string")
            "toInt" -> NamedType("int")
            "toFloat" -> NamedType("float")
            else -> error("unknown method '${expr.method}'")
        }

        is Unary -> when(expr.op) {
            UnOp.Not -> NamedType("bool")
        }
    }

    private fun llTypeOf(t: NamedType) = when (t.name) {
        "int" -> "i32"
        "float" -> "double"
        "bool" -> "i1"
        "char" -> "i8"
        "string" -> "{ ptr, i32 }"
        else -> error("unknown type ${t.name}")
    }

    private fun zeroInit(ty: String) = when (ty) {
        "i1","i8","i32" -> "0"
        "double" -> "0.0"
        "{ ptr, i32 }" -> "{ ptr null, i32 0 }"
        else -> error("no zero init for $ty")
    }

    private fun numberOrCharToString(b: BlockBuilder, rv: RValue): RValue {
        val (ty, op) = asOperand(rv)
        val fmt = when(ty) { "i32","i1","i8" -> "%d"; "double" -> "%f"; else -> error("toString() not supported for type $ty") }

        val fmtRef = mod.internCString(fmt)
        val fmtPtr = b.gepGlobalFirst(fmtRef)

        val buf = b.allocaArray(64, "i8")
        val bufPtr = b.gepFirst(buf, 64, "i8")

        val written = b.callSnprintf(
            bufPtr, 64, fmtPtr,
            when (ty) { "i1","i8" -> "i32" to op; else -> ty to op }
        )

        val ssa = b.packString(bufPtr, written)
        return RValue.ValueReg("{ ptr, i32 }", ssa)
    }

    private fun stringToIntIfLiteral(recv: Expr): RValue {
        return when(recv) {
            is StringLit -> {
                val s = recv.value.trim()
                val v = s.toLongOrNull() ?: error("cannot convert string '$s' to int")
                RValue.Immediate("i32", v.toString())
            }

            else -> error("toInt() supported only on string literals")
        }
    }

    private fun stringToFloatIfLiteral(recv: Expr): RValue {
        return when(recv) {
            is StringLit -> {
                val s = recv.value.trim()
                val v = s.toDoubleOrNull() ?: error("cannot convert string '$s' to float")
                RValue.Immediate("double", v.toString())
            }

            else -> error("toFloat() supported only on string literals")
        }
    }
    
    private fun concatStrings(b: BlockBuilder, a: RValue, c: RValue): RValue {
        fun asStringParts(rv: RValue): Pair<String, String> {
            return when(rv) {
                is RValue.Aggregate -> {
                    val p = b.extractValue("{ ptr, i32 }", rv.literal, 0)
                    val l = b.extractValue("{ ptr, i32 }", rv.literal, 1)
                    p to l
                }
                
                is RValue.ValueReg -> {
                    require(rv.llTy == "{ ptr, i32 }") { "expected string aggregate, got ${rv.llTy}" }
                    val p = b.extractValue("{ ptr, i32 }", rv.reg, 0)
                    val l = b.extractValue("{ ptr, i32 }", rv.reg, 1)
                    p to l
                }
                
                is RValue.Immediate -> error("cannot use immediate as string")
            }
        }
        
        val (aPtr, aLen) = asStringParts(a)
        val (cPtr, cLen) = asStringParts(c)
        val totalLen = b.add("i32", aLen, cLen)
        
        val one = "1"
        val capI32 = b.add("i32", totalLen, one)
        val capI64 = b.zext("i32", capI32, "i64")
        val buf = b.call("malloc", "ptr", "i64" to capI64)
        
        val aLen64 = b.zext("i32", aLen, "i64")
        b.call("memcpy", "ptr", "ptr" to buf, "ptr" to aPtr, "i64" to aLen64)
        
        val dst2 = b.gepByteOffset(buf, aLen)
        val cLen64 = b.zext("i32", cLen, "i64")
        b.call("memcpy", "ptr", "ptr" to dst2, "ptr" to cPtr, "i64" to cLen64)
        
        val nulPtr = b.gepByteOffset(buf, totalLen)
        b.store("i8", "0", nulPtr)
        
        val packed = b.packString(buf, totalLen)
        return RValue.ValueReg("{ ptr, i32 }", packed)
    }

    private fun asOperand(rv: RValue): Pair<String, String> = when(rv) {
        is RValue.Immediate -> rv.llTy to rv.text
        is RValue.ValueReg -> rv.llTy to rv.reg
        is RValue.Aggregate -> error("cannot use aggregate as operand")
    }

    private fun utf8Len(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun fresh(base: String) = "${base}_${labelCounter++}"

    private fun extractStringPtr(b: BlockBuilder, rv: RValue): String = when (rv) {
        is RValue.Aggregate -> b.extractValue("{ ptr, i32 }", rv.literal, 0)
        is RValue.ValueReg  -> b.extractValue("{ ptr, i32 }", rv.reg, 0)
        else -> error("expected string aggregate")
    }
}
