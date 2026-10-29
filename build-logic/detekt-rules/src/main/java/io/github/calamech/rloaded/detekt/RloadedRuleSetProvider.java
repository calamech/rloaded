package io.github.calamech.rloaded.detekt;

import io.gitlab.arturbosch.detekt.api.Config;
import io.gitlab.arturbosch.detekt.api.RuleSet;
import io.gitlab.arturbosch.detekt.api.RuleSetProvider;
import java.util.List;

/** Provides the {@code rloaded} rule set to detekt. */
public final class RloadedRuleSetProvider implements RuleSetProvider {
  @Override
  public String getRuleSetId() {
    return "rloaded";
  }

  @Override
  public RuleSet instance(Config config) {
    return new RuleSet(getRuleSetId(), List.of(new NoNotNullAssertion(config)));
  }
}
