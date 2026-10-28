"use client"

import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react"
import { useRouter } from "next/navigation"
import { toast } from "sonner"
import { create } from "zustand"
import { Button } from "@/components/ui/button"
import { GlassGroup, GlassRow } from "@/components/ui/glass-list"
import { SKIP_BACK, SKIP_FORWARD, skipBy, usePodcastPrefs } from "@/components/podcast/podcast-playback"
import { describePauses, pauseLog, type PauseEntry } from "@/lib/pause-log"
import { useProvider } from "@/lib/providers"
import { useAuthenticatedArtwork } from "@/hooks/use-authenticated-artwork"
import { usePlayer } from "@/stores/player"
import { interruption } from "./interruption-controller"
import { useProfile } from "@/stores/profile"
import { useSession } from "@/stores/session"
import { searchHref } from "@/stores/search"
import { browseAuto, resolveAutoPlay, searchAuto } from "@/lib/android-auto"

/** Exposed by the Android shell (DecibelMedia.kt) through addJavascriptInterface. */
interface DecibelNative {
  update(json: string): void
  artwork(id: string, base64: string): void
  artworkUrl?(id: string, url: string): void
  stop(): void
  // 0.1.5 shell (bridge version 2)
  notificationsEnabled?(): boolean
  requestNotifications?(): void
  openNotificationSettings?(): void
  diagnostics?(): string
  // 0.1.6 shell (bridge version 3)
  shareImage?(base64: string, mime: string, text: string): boolean
  // 0.1.9 shell (bridge version 5): app updates
  version?(): number
  installUpdate?(url: string, sha256: string, version: string): boolean
  updateStatus?(): string
  openUrl?(url: string): void
  /** bridge 6 (0.1.11): installer with the APK already downloaded, after granting «Instalar apps desconocidas» */
  resumeInstall?(): boolean
  canInstallPackages?(): boolean
  /** Bridge 7: Android Auto uses the same authenticated provider and player as the phone. */
  autoReady?(ready: boolean): void
  autoResult?(request: number, json: string): void
  autoRequestActive?(request: number): boolean
  autoRefresh?(): void
  /** Bridge 8: text shared to Decibel from another app (read once, then cleared). */
  sharedText?(): string
}

/** Notification permission as last reported by the shell (null: not Android / unknown). */
const useNotificationState = create<{ enabled: boolean | null }>()(() => ({ enabled: null }))
const readNotifications = () => {
  try { return native()?.notificationsEnabled?.() ?? null } catch { return null }
}
const refreshNotifications = () => useNotificationState.setState({ enabled: readNotifications() })

/** Ajustes → Reproducción: notification state and a button to the dialog / system settings (Android only). */
export function NotificationSettingRow({ icon }: { icon?: ReactNode }) {
  const supported = useSyncExternalStore(() => () => {}, () => !!native()?.notificationsEnabled, () => false)
  const enabled = useNotificationState((s) => s.enabled)
  const [diag, setDiag] = useState<string | null>(null)
  useEffect(() => {
    if (!supported) return
    refreshNotifications()
    const onVisible = () => { if (document.visibilityState === "visible") refreshNotifications() }
    document.addEventListener("visibilitychange", onVisible)
    return () => document.removeEventListener("visibilitychange", onVisible)
  }, [supported])
  if (!supported) return null
  return (
    <GlassGroup>
      <GlassRow icon={icon} label="Notificación de reproducción">
        {enabled ? (
          <span className="text-sm text-muted-foreground">Activada</span>
        ) : (
          <Button size="sm" className="h-8 rounded-full px-3.5" onClick={() => native()?.requestNotifications?.()}>Activar</Button>
        )}
      </GlassRow>
      <button
        type="button"
        className="w-full px-4 py-3 text-left text-[13px] whitespace-pre-line text-muted-foreground"
        onClick={() => {
          try {
            const d = JSON.parse(native()?.diagnostics?.() ?? "{}") as Record<string, unknown>
            const service = d.service === "foreground" ? "en primer plano" : d.service === "background" ? "en pausa" : "parado"
            const pauses = describePauses([...pauseLog(), ...((Array.isArray(d.pauses) ? d.pauses : []) as PauseEntry[])])
            setDiag(`Servicio ${service} · ${d.updates ?? 0} avisos del reproductor · permiso ${d.permission ?? "?"}${d.serviceError ? ` · ${d.serviceError}` : ""}` +
              `\nÚltimas pausas: ${pauses.length ? pauses.join(" · ") : "ninguna fuera de la app"}`)
          } catch { setDiag("Sin datos del servicio") }
        }}
      >
        {diag ?? "Comprobar reproducción en segundo plano"}
      </button>
    </GlassGroup>
  )
}

declare global {
  interface Window {
    DecibelNative?: DecibelNative
    __decibelNative?: (command: string, arg: number | null) => void
    __decibelAuto?: (request: { id: number; action: string; value: string }) => void
  }
}

const native = () => (typeof window === "undefined" ? undefined : window.DecibelNative)

/** Android share sheet for an image (the WebView has no Web Share API). False when the shell cannot do it. */
export function nativeShareImage(dataUrl: string, mime: string, text: string) {
  try { return native()?.shareImage?.(dataUrl, mime, text) ?? false } catch { return false }
}

/**
 * Android only: mirrors the player into the native MediaSession / foreground service (notification,
 * lock screen, headset buttons, background playback) and runs the commands it sends back (notification
 * buttons, audio focus, headphones unplugged). A no-op in browsers, where navigator.mediaSession is used.
 */
export function NativeMediaBridge() {
  const track = usePlayer((s) => s.queue[s.index])
  const provider = useProvider()
  const profileStatus = useProfile((s) => s.status)
  const mode = useSession((s) => s.mode)
  const cover = useAuthenticatedArtwork(provider.coverUrl(track?.coverArt, 512))
  const ducked = useRef<number | null>(null)
  const router = useRouter()

  useEffect(() => {
    const bridge = native()
    if (!bridge?.autoReady || !bridge.autoResult) return
    const ready = !!mode && ["ready", "offline", "unavailable"].includes(profileStatus)
    let disposed = false
    let playbackRequest = 0
    window.__decibelAuto = async ({ id, action, value }) => {
      if (!ready || disposed) return
      const playRequest = action === "browse" ? 0 : ++playbackRequest
      try {
        const queue = usePlayer.getState().queue
        if (action === "browse") {
          const items = await browseAuto(provider, queue, value)
          if (!disposed) bridge.autoResult!(id, JSON.stringify({ items }))
          return
        }
        const selection = action === "search"
          ? await searchAuto(provider, queue, value)
          : await resolveAutoPlay(provider, queue, value)
        if (disposed || playRequest !== playbackRequest || !bridge.autoRequestActive?.(id)) return
        const player = usePlayer.getState()
        if ("inQueue" in selection && selection.inQueue) {
          // Queue may have changed while the request was in flight.
          const track = selection.tracks[selection.index]
          const index = player.queue[selection.index]?.id === track.id ? selection.index : player.queue.findIndex((t) => t.id === track.id)
          if (index < 0) throw new Error("La canción ya no está en la cola")
          player.jumpTo(index)
        } else player.playList(selection.tracks, selection.index, selection.label)
        bridge.autoResult!(id, JSON.stringify({ items: [] }))
      } catch (error) {
        if (!disposed && (action === "browse" || playRequest === playbackRequest)) bridge.autoResult!(id, JSON.stringify({
          error: error instanceof Error ? error.message : "No se pudo cargar la música",
        }))
      }
    }
    bridge.autoReady(ready)
    let queue = usePlayer.getState().queue
    const offQueue = usePlayer.subscribe((state) => {
      if (state.queue !== queue) { queue = state.queue; bridge.autoRefresh?.() }
    })
    return () => {
      disposed = true
      offQueue()
      delete window.__decibelAuto
      bridge.autoReady!(false)
    }
  }, [provider, profileStatus, mode])

  // native → JS
  useEffect(() => {
    if (!native()) return
    // A link shared from YouTube (or any text) opens in the search, which already resolves YouTube URLs.
    const openShared = () => { const text = native()?.sharedText?.(); if (text) router.push(searchHref(text)) }
    openShared()
    window.__decibelNative = (command, arg) => {
      const p = usePlayer.getState()
      switch (command) {
        case "share": openShared(); break
        case "play": if (p.queue.length) p.setPlaying(true); break
        case "pause": p.setPlaying(false); break
        case "next": void p.next(); break
        case "prev": p.prev(); break
        case "forward": skipBy(SKIP_FORWARD); break
        case "back": skipBy(-SKIP_BACK); break
        case "notifications": {
          refreshNotifications()
          if (!arg) toast("Notificaciones desactivadas", {
            description: "La música sigue sonando, pero quizá no veas los controles fuera de la app.",
            action: { label: "Activar", onClick: () => native()?.requestNotifications?.() },
          })
          break
        }
        case "seek": if (arg != null) p.seek(arg); break
        // A call, a voice note or another app recording began (1) or ended (0): fade out and pause / fade back in.
        case "interrupt": void (arg ? interruption.begin() : interruption.end()); break
        case "duck": {
          const audio = p.audio
          if (!audio) break
          if (arg) { if (ducked.current == null) { ducked.current = audio.volume; audio.volume = audio.volume * 0.25 } }
          else if (ducked.current != null) { audio.volume = ducked.current; ducked.current = null }
          break
        }
      }
    }
    return () => { delete window.__decibelNative }
  }, [router])

  // JS → native: state, sent on changes and on seeks (native extrapolates the position while playing)
  useEffect(() => {
    const bridge = native()
    if (!bridge) return
    let last = { id: "", playing: false, duration: 0, position: 0, at: 0, rate: 1 }
    const send = () => {
      const s = usePlayer.getState()
      const t = s.queue[s.index]
      const playing = !!t && s.isPlaying
      const now = performance.now()
      const expected = last.position + (last.playing ? ((now - last.at) / 1000) * last.rate : 0)
      const rate = t?.podcast ? usePodcastPrefs.getState().rate : 1
      const changed = (t?.id ?? "") !== last.id || playing !== last.playing || rate !== last.rate || Math.abs((s.duration || t?.duration || 0) - last.duration) > 0.5
      if (!changed && Math.abs(s.position - expected) < 2 && now - last.at < 30_000) return
      last = { id: t?.id ?? "", playing, duration: s.duration || t?.duration || 0, position: s.position, at: now, rate }
      try {
        bridge.update(JSON.stringify(t ? { id: t.id, title: t.title, artist: t.artist, album: t.album ?? "", duration: last.duration, position: s.position, playing, podcast: !!t.podcast, rate } : { id: "" }))
      } catch { /* shell without the bridge */ }
    }
    send()
    const offRate = usePodcastPrefs.subscribe(send)
    const offPlayer = usePlayer.subscribe(send)
    return () => { offRate(); offPlayer() }
  }, [])

  // artwork: authenticated covers are blob: URLs (read here); public ones are downloaded natively
  useEffect(() => {
    const bridge = native()
    if (!bridge || !track || !cover) return
    const id = track.id
    if (!cover.startsWith("blob:") && bridge.artworkUrl) { bridge.artworkUrl(id, cover); return }
    const controller = new AbortController()
    fetch(cover, { signal: controller.signal })
      .then((response) => response.blob())
      .then((blob) => new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result).split(",")[1] ?? "")
        reader.onerror = () => reject(reader.error)
        reader.readAsDataURL(blob)
      }))
      .then((base64) => { if (!controller.signal.aborted && base64) bridge.artwork(id, base64) })
      .catch(() => {})
    return () => controller.abort()
  }, [track, cover])

  return null
}
