package com.example.config.pricing.refresh;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Captures which property keys changed, for the audit record.
 *
 * <p>{@link EnvironmentChangeEvent} is the only event carrying the changed key set, but it is also
 * the event {@code ConfigurationPropertiesRebinder} listens on, and listener order between the two
 * is not guaranteed. This listener therefore reads only the event payload and never touches a
 * properties bean, so it is order-independent by construction. It still declares highest precedence
 * so the keys are recorded before any other listener can fail the publication chain.
 */
@Component
public class EnvironmentChangeKeyRecorder
    implements ApplicationListener<EnvironmentChangeEvent>, Ordered {

  private final AtomicReference<List<String>> lastChangedKeys = new AtomicReference<>(List.of());

  @Override
  public void onApplicationEvent(EnvironmentChangeEvent event) {
    this.lastChangedKeys.set(event.getKeys().stream().sorted().toList());
  }

  /** Key NAMES only. Values are never captured, because a change may carry a decrypted secret. */
  public List<String> lastChangedKeys() {
    return this.lastChangedKeys.get();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
