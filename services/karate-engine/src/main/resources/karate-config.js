// The platform's karate-config.js, shipped inside the adapter jar so that Karate finds it on the classpath
// before looking anywhere else. Tenant source is never on the classpath, so tenant files cannot replace it.
//
// It does one thing: under an allowlist it points Karate's HTTP client at the execution's egress proxy, with
// the credential the proxy checks against the control plane on every request. The proxy is where the
// allowlist is enforced; this only tells the client where the proxy is. A DENY_ALL run has no `egress` and
// no network, and this does nothing.
//
// Tenant code can reconfigure the proxy, read the credential, or talk to the network directly -- the
// sandbox's only route is the proxy, so none of that reaches a destination the policy does not permit.
function fn() {
  if (kaas && kaas.egress) {
    karate.configure('proxy', { uri: kaas.egress.uri, username: 'kaas', password: kaas.egress.token });
  }
  return {};
}
