package com.example.rhbk;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class PasswordExpiryIdmAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "idm-password-expiry-check";

  private static final List<ProviderConfigProperty> CONFIG = List.of(
    prop(PasswordExpiryIdmAuthenticator.CFG_ATTR,
        "User attribute name",
        "User attribute containing krbPasswordExpiration (GeneralizedTime, e.g. 20250904000000Z).",
        ProviderConfigProperty.STRING_TYPE, "krbPasswordExpiration"),
    prop(PasswordExpiryIdmAuthenticator.CFG_WARN_DAYS,
        "Warn/force threshold (days)",
        "If password expires in N days or less, force UPDATE_PASSWORD.",
        ProviderConfigProperty.STRING_TYPE, "7")
  );

  private static ProviderConfigProperty prop(String name, String label, String help, String type, String def) {
    ProviderConfigProperty p = new ProviderConfigProperty();
    p.setName(name);
    p.setLabel(label);
    p.setHelpText(help);
    p.setType(type);
    p.setDefaultValue(def);
    return p;
  }

  @Override public String getId() { return ID; }
  @Override public String getDisplayType() { return "Password Expiry Check (IdM/LDAP)"; }

  // NEW: required by ConfigurableAuthenticatorFactory in KC 26
  @Override public String getReferenceCategory() { 
    // Any non-null string is fine; used for UI grouping and references.
    return "password-expiry"; 
  }

  @Override public String getHelpText() {
    return "Reads krbPasswordExpiration from a user attribute and triggers UPDATE_PASSWORD if within threshold.";
  }
  @Override public boolean isConfigurable() { return true; }
  @Override public List<ProviderConfigProperty> getConfigProperties() { return CONFIG; }
  @Override public Authenticator create(KeycloakSession session) { return new PasswordExpiryIdmAuthenticator(); }
  @Override public void init(Config.Scope config) { }
  @Override public void postInit(org.keycloak.models.KeycloakSessionFactory factory) { }
  @Override public void close() { }

  @Override public boolean isUserSetupAllowed() { return false; }

  @Override
  public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
    return new AuthenticationExecutionModel.Requirement[] {
      AuthenticationExecutionModel.Requirement.REQUIRED,
      AuthenticationExecutionModel.Requirement.ALTERNATIVE,
      AuthenticationExecutionModel.Requirement.DISABLED
    };
  }
}
