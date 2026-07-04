import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import { default as cs } from "./cs.json";
import { default as da } from "./da.json";
import { default as de } from "./de.json";
import { default as en } from "./en.json";
import { default as es } from "./es.json";
import { default as fi } from "./fi.json";
import { default as fr } from "./fr.json";
import { default as hu } from "./hu.json";
import { default as it } from "./it.json";
import { default as nl } from "./nl.json";
import { default as no } from "./no.json";
import { default as pl } from "./pl.json";
import { default as sk } from "./sk.json";
import { default as sv } from "./sv.json";

import { configuredLanguageList } from "@/config/config";
import { useBrowserLanguage } from "@/hooks/use-current-lang";
import { getSelectedNscCode } from "@/services/app-context.service";
import { translationsApi } from "@/services/b2b-api";
import { router } from "@/router";

export const supportedLanguages = configuredLanguageList.filter(c => c.enabled).map(c => c.languageCode);
export const fallbackLanguage = "en";
const browserLanguage = useBrowserLanguage();
// route language is after the hash in the url, e.g. #/en/dashboard. get it if present.
const routeLanguageMatch = window.location.hash.match(/^#\/([a-z]{2})(\/|$)/);
const routeLanguage = routeLanguageMatch ? routeLanguageMatch[1] : null;
export const userLanguage = supportedLanguages.includes(browserLanguage || '') ? browserLanguage : fallbackLanguage;
const activeLanguage = routeLanguage || userLanguage || fallbackLanguage;
console.log("Detected user language:", userLanguage, "Browser language:", browserLanguage, "i18n.language:", i18n.language);
const i18nInitPromise = i18n.use(initReactI18next).init({
    fallbackLng: fallbackLanguage, // if you're using a language detector, do not define the lng option
    lng: activeLanguage,
    supportedLngs: supportedLanguages,
    resources: {
        en: {
            translation: en,
        },
        cs: {
            translation: cs,
        },
        da: {
            translation: da,
        },
        de: {
            translation: de,
        },
        es: {
            translation: es,
        },
        fi: {
            translation: fi,
        },
        fr: {
            translation: fr,
        },
        hu: {
            translation: hu,
        },
        it: {
            translation: it,
        },
        nl: {
            translation: nl,
        },
        no: {
            translation: no,
        },
        pl: {
            translation: pl,
        },
        sk: {
            translation: sk,
        },
        sv: {
            translation: sv,
        },
    },
});
i18n.on("languageChanged", async (lng) => {
    console.log("Language changed to :", lng);
    // await loadTranslationOverrides(lng);
});
void i18nInitPromise.then(() => {
    loadTranslationOverrides(i18n.language);
});
export function refreshBundle(languageCode: string) {
    if (i18n.language !== languageCode) {
        return;
    }
    //reload the browser so that the resource bundle is reloaded
    window.location.reload();
    
}

export async function switchLanguage(languageCode: string) {
    await loadTranslationOverrides(languageCode);

    // Update the URL hash so a browser refresh will keep the selected language.
    const hash = window.location.hash || "#/";
    const match = hash.match(/^#\/([a-z]{2})(\/.*|$)/);
    let newHash: string;
    if (match) {
        newHash = `#/${languageCode}${match[2] || ""}`;
    } else {
        const rest = hash.startsWith("#/") ? hash.slice(2) : hash.slice(1);
        newHash = `#/${languageCode}${rest ? "/" + rest : ""}`;
    }
    if (newHash !== hash) {
        window.location.hash = newHash;
    }

    // Tell i18n to change language (async) — router may pick up the hash change.
    await i18n.changeLanguage(languageCode);
}
// Fetch NSC-specific overrides and merge them into the baseline resource bundle.
async function loadTranslationOverrides(languageCode: string) {
    var nscCode = getSelectedNscCode();
    if (!nscCode) {
        nscCode = "*";
    }

    try {
        const response = await translationsApi.getTranslationOverrides({
            languageCode,
            nscCode,
        });

        const overrides = response.data ?? {};
        if (!overrides || Object.keys(overrides).length === 0) {
            return;
        }

        Object.entries(overrides).forEach(([key, value]) => {
            if (typeof value === "string") {
                i18n.addResource(languageCode, "translation", key, value);
            }
        });
    } catch (error) {
        console.error("Failed to load translation overrides", error);
    }
}

export const getTranslator = () => {
    return (key: string, options?: Record<string, any>) => {
        return i18n.t(key, options);
    };
};

export default i18n;
