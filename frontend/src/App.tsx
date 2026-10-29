import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import "./i18n";
import Workspace from "./Workspace";
export default function App() {
  const { t, i18n } = useTranslation();
  const [admin, setAdmin] = useState(false);
  const [authenticated, setAuthenticated] = useState(false),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false);
  const [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [error, setError] = useState("");
  const sessionExpired = useCallback(() => {
    setAuthenticated(false);
    setError("sessionExpired");
  }, []);
  useEffect(() => {
    fetch("/api/auth/me")
      .then(async (r) => {
        setAuthenticated(r.ok);
        if (r.ok) {
          const me = await r.json();
          setAdmin(me.admin === true);
        }
      })
      .catch(() => setError("network"))
      .finally(() => setLoading(false));
  }, []);
  async function submit(event?: FormEvent) {
    event?.preventDefault();
    setBusy(true);
    setError("");
    try {
      const csrf = await fetch("/api/auth/csrf");
      if (!csrf.ok) throw new Error();
      const { token } = await csrf.json();
      const response = await fetch(
        "/api/auth/" + (authenticated ? "logout" : "login"),
        {
          method: "POST",
          headers: {
            "X-CSRF-TOKEN": token,
            "Content-Type": "application/x-www-form-urlencoded",
          },
          body: authenticated
            ? undefined
            : new URLSearchParams({ username, password }),
        },
      );
      if (!response.ok) {
        setError(response.status === 401 ? "error" : "network");
        return;
      }
      setAuthenticated(!authenticated);
      setAdmin(false);
      if (!authenticated) {
        const me = await fetch("/api/auth/me");
        if (me.ok) {
          const body = await me.json();
          setAdmin(body.admin === true);
        }
      }
      setPassword("");
    } catch {
      setError("network");
    } finally {
      setBusy(false);
    }
  }
  function language() {
    const next = i18n.language === "zh" ? "en" : "zh";
    void i18n.changeLanguage(next);
    localStorage.setItem("language", next);
    document.documentElement.lang = next === "zh" ? "zh-CN" : "en";
  }
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="flex h-18 items-center justify-between bg-slate-950 px-8 text-white">
        <span className="text-lg font-semibold tracking-wide">TjLightRAG</span>
        <nav aria-label={t("navigation.account")} className="flex gap-6">
          <button onClick={language}>
            {i18n.language === "zh" ? "English" : "中文"}
          </button>
          {authenticated && (
            <button disabled={busy} onClick={() => void submit()}>
              {t("logout")}
            </button>
          )}
        </nav>
      </header>
      {loading ? (
        <p className="p-8">{t("loading")}</p>
      ) : !authenticated ? (
        <main className="mx-auto mt-24 max-w-md rounded-2xl border border-slate-200 bg-white p-10 shadow-sm">
          <h1 className="text-3xl font-semibold">{t("welcome")}</h1>
          <p className="mt-3 mb-8 text-sm text-slate-500">{t("subtitle")}</p>
          <form onSubmit={submit} className="space-y-5">
            <label className="block">
              {t("username")}
              <input
                autoComplete="username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </label>
            <label className="block">
              {t("password")}
              <input
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <button
              disabled={busy}
              className="w-full rounded-lg bg-blue-600 py-3 font-medium text-white disabled:opacity-50"
            >
              {busy ? t("loading") : t("login")}
            </button>
          </form>
          {error && (
            <p role="alert" className="mt-4 text-red-700">
              {t(error)}
            </p>
          )}
        </main>
      ) : (
        <main aria-label="Workspace">
          <Workspace admin={admin} onSessionExpired={sessionExpired} />
        </main>
      )}
      {authenticated && error && (
        <p role="alert" className="p-8 text-red-700">
          {t(error)}
        </p>
      )}
    </div>
  );
}
