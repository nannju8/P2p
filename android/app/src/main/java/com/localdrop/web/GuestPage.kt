package com.localdrop.web

/** Page invité 100 % autonome : CSS et SVG inline, zéro requête externe. */
object GuestPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(appName: String, version: String, sizeBytes: Long, pinRequired: Boolean): String {
        val mb = "%.1f".format(sizeBytes / 1048576.0)
        val action = if (pinRequired)
            """<form action="/download" method="get"><input name="pin" inputmode="numeric" pattern="[0-9]{4}" maxlength="4" placeholder="Code à 4 chiffres" required>
               <button class="btn" type="submit">Télécharger l'application (.apk)</button></form>"""
        else """<a class="btn" href="/download">Télécharger l'application (.apk)</a>"""
        return """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(appName)}</title>
<style>
*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:20px}
.card{max-width:420px;width:100%;background:#1e293b;border-radius:24px;padding:28px;text-align:center}
.logo{width:72px;height:72px;margin:0 auto 12px}h1{margin:.2em 0}.meta{color:#94a3b8;margin-bottom:22px}
.btn{display:block;width:100%;padding:18px;border:0;border-radius:16px;background:#6366f1;color:#fff;font-size:18px;font-weight:700;text-decoration:none}
input{width:100%;padding:14px;margin-bottom:12px;border-radius:12px;border:1px solid #475569;background:#0f172a;color:#fff;font-size:20px;text-align:center;letter-spacing:6px}
ol{text-align:left;color:#cbd5e1;line-height:1.5;margin-top:24px;padding-left:20px}
</style></head><body><div class="card">
<svg class="logo" viewBox="0 0 72 72"><rect width="72" height="72" rx="18" fill="#6366f1"/><path d="M20 38l16-16 16 16M36 22v30" stroke="#fff" stroke-width="6" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>
<h1>${esc(appName)}</h1><div class="meta">Version ${esc(version)} · $mb Mo</div>
$action
<ol><li>Téléchargez le fichier.</li><li>Ouvrez-le dans vos notifications/téléchargements et activez « Autoriser cette source » si Android vous le demande.</li></ol>
</div></body></html>"""
    }
}
