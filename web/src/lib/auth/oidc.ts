import { UserManager, WebStorageStateStore } from "oidc-client-ts";
import { KEYCLOAK_REALM, KEYCLOAK_URL, OIDC_CLIENT_ID } from "../config";

let manager: UserManager | null = null;

export function userManager(): UserManager {
  if (manager === null) {
    manager = new UserManager({
      authority: `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}`,
      client_id: OIDC_CLIENT_ID,
      redirect_uri: `${window.location.origin}/callback`,
      post_logout_redirect_uri: window.location.origin,
      response_type: "code",
      scope: "openid",
      automaticSilentRenew: true,
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    });
  }
  return manager;
}
