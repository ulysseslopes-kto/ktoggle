<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${(locale.currentLanguageTag)!'pt-BR'}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#000000">
    <title>${msg("loginTitle", "ktoggle")}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/favicon.svg">
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet">
        </#list>
    </#if>
</head>
<body class="${properties.kcBodyClass!} ${bodyClass}">
<main class="kt-shell">
    <section class="kt-brand" aria-hidden="true">
        <div class="kt-logo">
            <span class="kt-switch"><span></span></span>
            <span class="kt-wordmark">kto<span class="kt-red">ggle</span></span>
        </div>
        <div class="kt-pitch">
            <p class="kt-headline">Feature flags<br><span class="kt-red">auditáveis</span></p>
            <ul>
                <li>Toda alteração passa por draft e revisão</li>
                <li>Configuração publicada em bundles assinados e imutáveis</li>
                <li>Qualquer decisão pode ser reproduzida</li>
            </ul>
        </div>
        <p class="kt-foot">KTO · ktoggle</p>
    </section>

    <section class="kt-panel">
        <div class="kt-card">
            <div class="kt-logo kt-logo-compact">
                <span class="kt-switch"><span></span></span>
                <span class="kt-wordmark">kto<span class="kt-red">ggle</span></span>
            </div>

            <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
                <h1 class="kt-title"><#nested "header"></h1>
            <#else>
                <#nested "show-username">
                <div class="kt-attempted">
                    <span>${auth.attemptedUsername}</span>
                    <a href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
                </div>
            </#if>

            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="${properties.kcAlertClass!} kt-alert-${message.type}" role="alert">
                    <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
                </div>
            </#if>

            <#nested "form">

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                    <input type="hidden" name="tryAnotherWay" value="on">
                    <a href="#" class="kt-link" onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                </form>
            </#if>

            <#nested "socialProviders">

            <#if displayInfo>
                <div class="kt-info"><#nested "info"></div>
            </#if>
        </div>
    </section>
</main>
</body>
</html>
</#macro>
