/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.isIrrefutable
import org.rust.lang.core.psi.ext.isStdOptionOrResult
import org.rust.lang.core.resolve.knownItems
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.type

class IfLetToMatchIntention : RsElementBaseIntentionAction<IfLetToMatchIntention.Context>() {
    override fun getText(): String = "Convert if let statement to match"
    override fun getFamilyName(): String = text

    data class Context(
        val ifStmt: RsIfExpr,
        val target: RsExpr,
        val matchArms: MutableList<MatchArm>,
        var elseBody: RsBlock?
    )

    data class MatchArm(
        val matchArm: RsPat,
        val body: RsBlock
    )

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        //1) Check that we have an if statement
        var ifStatement = element.ancestorStrict<RsIfExpr>() ?: return null

        // We go up in the tree to detect cases like `... else if let Some(value) = x { ... }`
        // and select the correct if statement

        while (ifStatement.parent is RsElseBranch) {
            // In that case
            // typeof(if.parent) = RsElseBranch ==> typeof(if.parent.parent) = RsIfExpr
            ifStatement = ifStatement.parent.parent as? RsIfExpr ?: return null
        }

        //Here we have extracted the upper most if statement node

        return extractIfLetStatementIfAny(ifStatement)
    }

    override fun invoke(project: Project, editor: Editor, ctx: Context) {
        val (ifStmt, target, matchArms, elseBody) = ctx
        val item = (target.type as? TyAdt)?.item as? RsEnumItem

        val generatedCode = buildString {
            append("match ")
            append(target.text)
            append(" {")
            matchArms.forEach {
                append('\n')
                append(it.matchArm.text)
                append(" => ")
                append(it.body.text)
            }
            if (elseBody != null || !isExhaustiveOptionOrResult(item, matchArms)) {
                append('\n')
                append(missingBranch(item, matchArms))
                append(" => ")
                append(elseBody?.text ?: "{}")
            }
            append("}")
        }

        val matchExpression = RsPsiFactory(project).createExpression(generatedCode) as RsMatchExpr
        ifStmt.replace(matchExpression)
    }

    private fun isExhaustiveOptionOrResult(item: RsEnumItem?, arms: List<MatchArm>): Boolean {
        if (item == null || !item.isStdOptionOrResult) return false
        if (arms.any { !it.matchArm.isIrrefutable }) return false

        return arms.map { it.matchArm.text.substringBefore('(') }.let {
            if (item == item.knownItems.Option) {
                "Some" in it && "None" in it
            } else {
                "Ok" in it && "Err" in it
            }
        }
    }

    private fun missingBranch(item: RsEnumItem?, arms: List<MatchArm>): String {
        if (item == null || !item.isStdOptionOrResult || arms.size != 1) return "_"
        val pat = arms.first().matchArm
        if (!pat.isIrrefutable) return "_"
        return when (pat.text.substringBefore('(')) {
            "Some" -> "None"
            "Ok" -> "Err(..)"
            "Err" -> "Ok(..)"
            "None" -> "Some(..)"
            else -> "_"
        }
    }

    private fun extractIfLetStatementIfAny(iflet: RsIfExpr, ctx: Context? = null): Context? {

        val condition = iflet.condition ?: return null

        //2) Check that we have a let condition
        if (condition.let == null) {
            return null
        }

        //3) Extract the match arm condition
        val matchArmPat = condition.pat ?: return null

        //4) Extract the target
        val target = condition.expr

        //5) Extract the if body
        val ifBody = iflet.block ?: return null

        val matchArm = MatchArm(matchArmPat, ifBody)
        var context = if (ctx != null) {
            //If we reach this code, that mean we are in a `if let Some(value) = x { ... } else if let Other(value) = x { ... }` case
            //                                                                                  ^
            val newContext = ctx.copy()

            // Check that the target is the same
            // Otherwise that doesn't make sense
            if (newContext.target.text != target.text) {
                return null
            }

            newContext.matchArms.add(matchArm)

            newContext
        } else {
            val newContext = Context(iflet, target, mutableListOf(matchArm), null)

            newContext
        }

        //6) Extract else body if any

        if (iflet.elseBranch != null) {
            val elseBody = iflet.elseBranch!!

            if (elseBody.ifExpr != null) {
                // This particular case mean that we have an `else if` that we must handle
                context = extractIfLetStatementIfAny(elseBody.ifExpr!!, context) ?: return null
            } else if (elseBody.block != null) {
                //This will go in the `_ => { ... }` arm
                context.elseBody = elseBody.block
            }
        }

        return context
    }
}
