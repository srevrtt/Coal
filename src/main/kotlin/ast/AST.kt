package ast

import kotlinx.serialization.Serializable

@Serializable data class Program(val decls: List<Decl>)

@Serializable sealed interface Decl
@Serializable data class FnDecl(
    val name: String,
    val params: List<Param>,
    val returnType: TypeRef?,
    val body: Block
) : Decl

@Serializable data class Param(val name: String, val type: TypeRef)
@Serializable data class Block(val stmts: List<Stmt>)

@Serializable sealed interface Stmt
@Serializable data class VarDecl(
    val name: String,
    val annotatedType: TypeRef?,
    val init: Expr?,
    val isConst: Boolean
) : Stmt

@Serializable data class Assign(
    val name: String,
    val value: Expr
) : Stmt

@Serializable sealed interface Expr
@Serializable data class ExprStmt(val expr: Expr) : Stmt

@Serializable data class Binary(
    val op: BinOp,
    val left: Expr,
    val right: Expr
) : Expr

@Serializable enum class BinOp {
    Add, Sub, Mul, Div, Mod, Pow,
    Eq, Ne, Lt, Le, Gt, Ge,
    And, Or
}

@Serializable data class Call(
    val callee: String,
    val args: List<Expr>
) : Expr

@Serializable data class MethodCall(
    val receiver: Expr,
    val method: String,
    val args: List<Expr>
) : Expr

@Serializable data class IfStmt(
    val branches: List<IfBranch>,
    val elseBranch: Block?
) : Stmt

@Serializable data class WhileStmt(
    val body: Block
) : Stmt

@Serializable data class IfBranch(
    val condition: Expr,
    val body: Block
)

@Serializable data class Unary(
    val op: UnOp,
    val expr: Expr
) : Expr

@Serializable enum class UnOp { Not }

@Serializable data class Ident(val name: String) : Expr
@Serializable data class IntLit(val value: Long) : Expr
@Serializable data class FloatLit(val value: Double) : Expr
@Serializable data class BoolLit(val value: Boolean) : Expr
@Serializable data class CharLit(val value: Int) : Expr
@Serializable data class StringLit(val value: String) : Expr

@Serializable sealed interface TypeRef
@Serializable data class NamedType(val name: String) : TypeRef
