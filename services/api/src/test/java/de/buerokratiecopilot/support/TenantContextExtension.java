package de.buerokratiecopilot.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Clears the tenant context after every test.
 *
 * <p>This exists because of a recurring obstacle rather than a single failure. Tables are protected by
 * row-level security policies, and the application establishes the tenant context in its HTTP layer. Tests
 * that call services directly — which is most of them — were written before those policies existed, so they
 * call without a context and are refused, correctly.</p>
 *
 * <p>The wrong fix, which I applied five times, is to wrap each call: it does not scale, and every new
 * policy exposes the next set of call sites. The scalable fix is two lines: a test's tenant helper sets the
 * context once with {@link RequestContext#setTenantId}, and this extension clears it afterwards so that a
 * leaked tenant cannot decide what the next test sees.</p>
 *
 * <p>Registering it is deliberately not automatic. A base class or a meta-annotation would be tidier, but a
 * test that silently depends on ambient state is harder to read than one that names what it needs, and this
 * is a facility for tests that have established a tenant of their own.</p>
 */
public class TenantContextExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        RequestContext.clear();
    }
}
