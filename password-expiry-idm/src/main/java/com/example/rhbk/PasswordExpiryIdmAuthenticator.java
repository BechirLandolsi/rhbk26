package com.example.rhbk;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Reads krbPasswordExpiration (LDAP GeneralizedTime, e.g. 20250904000000Z) from a user attribute.
 * If expires in <= warnThresholdDays (default 7), forces UPDATE_PASSWORD.
 */
public class PasswordExpiryIdmAuthenticator implements Authenticator {

  static final String CFG_ATTR = "userAttributeName";      // default: krbPasswordExpiration
  static final String CFG_WARN_DAYS = "warnThresholdDays"; // default: 7

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    final UserModel user = context.getUser();
    if (user == null) {
      context.attempted();
      return;
    }

    var cfg = context.getAuthenticatorConfig() != null ? context.getAuthenticatorConfig().getConfig() : null;
    String attrName = (cfg != null && cfg.containsKey(CFG_ATTR)) ? cfg.get(CFG_ATTR) : "krbPasswordExpiration";
    int warnDays = 7;
    try {
      if (cfg != null && cfg.containsKey(CFG_WARN_DAYS)) {
        warnDays = Integer.parseInt(cfg.get(CFG_WARN_DAYS));
      }
    } catch (NumberFormatException ignored) {}

    String raw = user.getFirstAttribute(attrName);
    if (raw == null || raw.isBlank()) {
      context.success();
      return;
    }

    Long expiryEpochSeconds = parseGeneralizedTimeToEpochSeconds(raw.trim());
    if (expiryEpochSeconds == null) {
      context.success();
      return;
    }

    long now = Instant.now().getEpochSecond();
    long secondsLeft = expiryEpochSeconds - now;
    long daysLeft = (long) Math.floor(secondsLeft / 86400.0);

    if (secondsLeft <= (long) warnDays * 86400L) {
      // addRequiredAction is idempotent; no need to check existing set
      user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.name());
      context.getAuthenticationSession().setAuthNote("pwd-expiry-warning",
          "Password expires in " + Math.max(daysLeft, 0) + " day(s)");
    }

    context.success();
  }

  @Override public void action(AuthenticationFlowContext context) { /* no-op: no form */ }
  @Override public boolean requiresUser() { return true; }

  // KC 26 signatures:
  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    // no-op: we add the RA directly in authenticate() when threshold is met
  }

  @Override public void close() { }

  /** Parse LDAP GeneralizedTime: yyyyMMddHHmmssZ or yyyyMMddHHmmss±HH[mm]. */
  static Long parseGeneralizedTimeToEpochSeconds(String v) {
    try {
      if (v.matches("\\d{14}Z")) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
        return Instant.from(f.parse(v)).getEpochSecond();
      }
      if (v.matches("\\d{14}[+\\-]\\d{2}(:?\\d{2})?")) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmssX").withZone(ZoneOffset.UTC);
        return Instant.from(f.parse(v)).getEpochSecond();
      }
    } catch (Exception ignored) { }
    return null;
    }
}
