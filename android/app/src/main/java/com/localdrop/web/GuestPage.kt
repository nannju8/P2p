package com.localdrop.web

object GuestPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(appName: String, version: String, sizeBytes: Long, pinRequired: Boolean, errorMsg: String? = null): String {
        val mb = "%.1f".format(sizeBytes / 1048576.0)
        val errBlock = if (errorMsg != null) """<div class="err">${esc(errorMsg)}</div>""" else ""
        val action = if (pinRequired)
            """<form action="/download" method="get">
               <input name="pin" inputmode="numeric" pattern="[0-9]{4}" maxlength="4" placeholder="• • • •" required autocomplete="off">
               <button class="btn" type="submit">Télécharger l'application (.apk)</button>
               </form>"""
        else """<a class="btn" href="/download">Télécharger l'application (.apk)</a>"""

        return """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(appName)}</title>
<style>
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#0d1322;color:#dde2f8;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:20px}
.card{max-width:420px;width:100%;background:#191f2f;border:1px solid #2f3445;border-radius:24px;padding:28px;text-align:center;box-shadow:0 20px 50px rgba(0,0,0,.45)}
.logo{width:72px;height:72px;margin:0 auto 14px}
.badge{display:inline-flex;align-items:center;gap:6px;padding:5px 12px;border-radius:999px;background:rgba(78,222,163,.12);color:#4edea3;font-size:12px;font-weight:600;margin-bottom:10px}
h1{margin:.1em 0;font-size:26px;letter-spacing:-.01em}
.meta{color:#c7c4d7;font-size:14px;margin-bottom:22px}
.err{background:#93000a;color:#ffdad6;padding:10px 14px;border-radius:12px;font-size:13px;font-weight:600;margin-bottom:14px}
.btn{display:block;width:100%;padding:16px;border:0;border-radius:16px;background:linear-gradient(135deg,#8083ff,#494bd6);color:#fff;font-size:16px;font-weight:700;text-decoration:none;cursor:pointer;box-shadow:0 8px 24px rgba(99,102,241,.3)}
input{width:100%;padding:14px;margin-bottom:12px;border-radius:14px;border:1px solid #464554;background:#080e1d;color:#4cd7f6;font-size:24px;font-weight:700;text-align:center;letter-spacing:10px}
ol{text-align:left;color:#c7c4d7;font-size:13px;line-height:1.6;margin-top:22px;padding:14px 16px 14px 32px;background:#151b2b;border-radius:16px}
</style></head><body><div class="card">
<svg class="logo" viewBox="0 0 72 72"><rect width="72" height="72" rx="18" fill="#6366f1"/><path d="M20 38l16-16 16 16M36 22v30" stroke="#fff" stroke-width="6" fill="none" stroke-linecap="round" stroke-linejoin="round"/><circle cx="54" cy="18" r="5" fill="#4edea3"/></svg>
<div class="badge">Transfert local sans Internet</div>
<h1>${esc(appName)}</h1><div class="meta">Version ${esc(version)} · $mb Mo · APK Universel</div>
$errBlock
$action
<ol><li>Téléchargez le fichier d'installation.</li><li>Ouvrez-le depuis vos notifications et activez <strong>« Autoriser cette source »</strong> si Android le demande.</li></ol>
</div></body></html>"""
    }
}
