package io.github.calamech.rloaded.detekt;

import io.gitlab.arturbosch.detekt.api.CodeSmell;
import io.gitlab.arturbosch.detekt.api.Config;
import io.gitlab.arturbosch.detekt.api.Debt;
import io.gitlab.arturbosch.detekt.api.DefaultContext;
import io.gitlab.arturbosch.detekt.api.Entity;
import io.gitlab.arturbosch.detekt.api.Issue;
import io.gitlab.arturbosch.detekt.api.Rule;
import io.gitlab.arturbosch.detekt.api.Severity;
import java.util.List;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtPostfixExpression;

/**
 * Reports every {@code !!} operator.
 *
 * <p>detekt's own {@code UnsafeCallOnNullableType} needs type resolution, which this build does not
 * run, so this rule works on the syntax tree alone.
 */
public final class NoNotNullAssertion extends Rule {
  private final Issue issue =
      new Issue(
          "NoNotNullAssertion",
          Severity.Defect,
          "The !! operator throws on null. Handle the null case explicitly.",
          Debt.Companion.getFIVE_MINS());

  // Kotlin callers get DefaultContext as a default argument; Java has to name it.
  @SuppressWarnings("deprecation")
  public NoNotNullAssertion(Config config) {
    super(config, new DefaultContext());
  }

  @Override
  public Issue getIssue() {
    return issue;
  }

  @Override
  public void visitPostfixExpression(KtPostfixExpression expression) {
    super.visitPostfixExpression(expression);
    if (expression.getOperationToken() == KtTokens.EXCLEXCL) {
      report(
          new CodeSmell(
              issue,
              Entity.Companion.from(expression, 0),
              "Avoid !!; handle the null case explicitly.",
              List.of(),
              List.of()));
    }
  }
}
