// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._controllers.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Dev-only hosted login page for the VS Code plugin.
 *
 * <p>Mirrors the mock server's login page ({@code agent-sdk/src/mock/loginPage.ts}):
 * self-contained HTML with inline JS that posts to {@code /api/v1/auth/login},
 * redeems the returned JWT for a one-time code at
 * {@code /api/v1/auth/authorize-code}, then redirects the browser back to the
 * plugin's loopback listener with {@code ?code=...&state=...}.</p>
 *
 * <p>Disabled by default; set {@code MYRMEC_DEV_LOGIN_PAGE_ENABLED=true} to enable.
 * When disabled, this controller returns a plain body explaining that the page
 * is not configured.</p>
 */
@Controller
public class DevLoginPageController {

    @Value("${myrmec.dev-login-page.enabled:false}")
    private boolean enabled;

    @Value("${myrmec.dev-login-page.demo-email:demo@myrmec.dev}")
    private String demoEmail;

    @Value("${myrmec.dev-login-page.demo-password:Demo123!}")
    private String demoPassword;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String loginPage() {
        if (!enabled) {
            return "<!DOCTYPE html><html><body><h1>Dev login page is disabled</h1>"
                    + "<p>Set MYRMEC_DEV_LOGIN_PAGE_ENABLED=true to enable it.</p></body></html>";
        }

        String email = escapeHtml(demoEmail != null ? demoEmail : "");
        String password = escapeHtml(demoPassword != null ? demoPassword : "");

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>Myrmec sign-in</title>\n"
                + "<style>\n"
                + "  body { font-family: system-ui, sans-serif; background: #1e1e1e; color: #ddd;\n"
                + "         display: flex; justify-content: center; padding-top: 12vh; margin: 0; }\n"
                + "  .card { background: #252526; border-radius: 8px; padding: 32px;\n"
                + "          width: 360px; box-shadow: 0 4px 16px rgba(0,0,0,.4); }\n"
                + "  h1 { font-size: 20px; margin: 0 0 4px; color: #fff; }\n"
                + "  p.sub { color: #999; margin: 0 0 24px; font-size: 13px; }\n"
                + "  label { display: block; font-size: 12px; color: #aaa; margin: 14px 0 4px; }\n"
                + "  input { width: 100%; box-sizing: border-box; padding: 8px; border-radius: 4px;\n"
                + "          border: 1px solid #3c3c3c; background: #1e1e1e; color: #ddd; }\n"
                + "  button { width: 100%; margin-top: 22px; padding: 10px; border: 0;\n"
                + "           border-radius: 4px; background: #2f7cd6; color: #fff; font-size: 14px;\n"
                + "           cursor: pointer; }\n"
                + "  button:disabled { opacity: .5; cursor: default; }\n"
                + "  #error { color: #f66; font-size: 13px; min-height: 18px; margin-top: 12px; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"card\">\n"
                + "  <h1>Myrmec</h1>\n"
                + "  <p class=\"sub\">Sign in to authorize the VS Code extension.</p>\n"
                + "  <form id=\"form\">\n"
                + "    <label for=\"email\">Email</label>\n"
                + "    <input id=\"email\" name=\"email\" type=\"email\" value=\"" + email
                + "\" autocomplete=\"username\">\n"
                + "    <label for=\"password\">Password</label>\n"
                + "    <input id=\"password\" name=\"password\" type=\"password\" value=\"" + password
                + "\" autocomplete=\"current-password\">\n"
                + "    <button id=\"submit\" type=\"submit\">Sign in</button>\n"
                + "    <div id=\"error\"></div>\n"
                + "  </form>\n"
                + "</div>\n"
                + "<script>\n"
                + "(function () {\n"
                + "  \"use strict\";\n"
                + "  var params = new URLSearchParams(window.location.search);\n"
                + "  var redirectUri = params.get(\"redirectUri\");\n"
                + "  var state = params.get(\"state\");\n"
                + "  var form = document.getElementById(\"form\");\n"
                + "  var errorBox = document.getElementById(\"error\");\n"
                + "  var submit = document.getElementById(\"submit\");\n"
                + "\n"
                + "  if (!redirectUri || !state) {\n"
                + "    errorBox.textContent =\n"
                + "      \"Missing redirectUri or state parameter — start sign-in from the VS Code extension.\";\n"
                + "    submit.disabled = true;\n"
                + "    return;\n"
                + "  }\n"
                + "\n"
                + "  function fail(message) {\n"
                + "    errorBox.textContent = message;\n"
                + "    submit.disabled = false;\n"
                + "    submit.textContent = \"Sign in\";\n"
                + "  }\n"
                + "\n"
                + "  form.addEventListener(\"submit\", function (event) {\n"
                + "    event.preventDefault();\n"
                + "    submit.disabled = true;\n"
                + "    submit.textContent = \"Signing in…\";\n"
                + "    var email = document.getElementById(\"email\").value.trim();\n"
                + "    var password = document.getElementById(\"password\").value;\n"
                + "    if (!email || !password) {\n"
                + "      fail(\"Email and password are required.\");\n"
                + "      return;\n"
                + "    }\n"
                + "\n"
                + "    fetch(\"/api/v1/auth/login\", {\n"
                + "      method: \"POST\",\n"
                + "      headers: { \"Content-Type\": \"application/json\" },\n"
                + "      body: JSON.stringify({ email: email, password: password }),\n"
                + "    })\n"
                + "      .then(function (res) {\n"
                + "        if (!res.ok) {\n"
                + "          return res.json().then(function (body) {\n"
                + "            throw new Error(body.message || \"Login failed.\");\n"
                + "          });\n"
                + "        }\n"
                + "        return res.json();\n"
                + "      })\n"
                + "      .then(function (login) {\n"
                + "        var authHeader = \"Bearer \" + login.accessToken;\n"
                + "        return fetch(\"/api/v1/auth/authorize-code\", {\n"
                + "          method: \"POST\",\n"
                + "          headers: {\n"
                + "            \"Content-Type\": \"application/json\",\n"
                + "            \"Authorization\": authHeader\n"
                + "          },\n"
                + "          body: JSON.stringify({ redirectUri: redirectUri }),\n"
                + "        }).then(function (res) {\n"
                + "          if (!res.ok) {\n"
                + "            return res.json().then(function (body) {\n"
                + "              throw new Error(body.message || \"Could not issue an authorization code.\");\n"
                + "            });\n"
                + "          }\n"
                + "          return res.json();\n"
                + "        });\n"
                + "      })\n"
                + "      .then(function (issued) {\n"
                + "        window.location =\n"
                + "          redirectUri + \"?code=\" + encodeURIComponent(issued.code) +\n"
                + "          \"&state=\" + encodeURIComponent(state);\n"
                + "      })\n"
                + "      .catch(function (err) {\n"
                + "        fail(err && err.message ? err.message : \"Sign-in failed.\");\n"
                + "      });\n"
                + "  });\n"
                + "})();\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
