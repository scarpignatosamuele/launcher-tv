package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Play Store in the dock: instead of nothing, the carousel suggests well-known, legal Android TV apps that aren't
 * installed yet. Each card is the app's real Play Store icon on a blurred 16:9 backdrop of its own colors (bundled in
 * assets/store, made from the icon); OK opens the app's page in the Play Store. Package ids checked on Google Play
 * (Italy) on 2026-10-22. Edit freely.
 */
final class StoreApps {
    private StoreApps() {}

    static final String PLAY_STORE = "com.android.vending";

    /** Package, name, detail line (Italian). */
    private static final String[][] APPS = {
        {"com.netflix.ninja", "Netflix", "Film e serie · Abbonamento"},
        {"com.disney.disneyplus", "Disney+", "Film e serie · Abbonamento"},
        {"com.nowtv.it", "NOW", "Cinema, serie e sport · Abbonamento"},
        {"com.dazn", "DAZN", "Sport in diretta · Abbonamento"},
        {"it.rainet.androidtv", "RaiPlay", "TV italiana · Gratis"},
        {"it.mediaset.infinitytv", "Mediaset Infinity", "TV italiana · Gratis"},
        {"tv.pluto.android", "Pluto TV", "Canali e film · Gratis"},
        {"com.spotify.tv.android", "Spotify", "Musica e podcast · Gratis"},
        {"com.apple.atve.androidtv.appletv", "Apple TV", "Film e serie · Abbonamento"},
        {"com.cbs.ott", "Paramount+", "Film e serie · Abbonamento"},
        {"tv.wuaki.apptv", "Rakuten TV", "Film e serie · Gratis e noleggio"},
        {"com.mubi", "MUBI", "Cinema d'autore · Abbonamento"},
        {"com.plexapp.android", "Plex", "Media server e streaming · Gratis"},
        {"org.jellyfin.androidtv", "Jellyfin", "Media server personale · Open source"},
        {"org.videolan.vlc", "VLC", "Lettore video · Open source"},
        {"com.nvidia.geforcenow", "GeForce NOW", "Cloud gaming · Gratis"},
        {"com.valvesoftware.steamlink", "Steam Link", "Giochi dal PC · Gratis"},
        {"com.ionitech.airscreen", "AirScreen", "AirPlay e Cast · Gratis"},
        {"com.yablio.sendfilestotv", "Send files to TV", "Trasferisci file · Gratis"},
        {"com.lonelycatgames.Xplore", "X-plore", "File manager · Gratis"},
        {"com.cloudmosa.puffinTV", "Puffin TV", "Browser web · Gratis"},
    };

    /** The ones not installed on this TV, as carousel picks (OK: the app's Play Store page). */
    static List<Recs.Pick> picks(Context c, int max) {
        PackageManager pm = c.getPackageManager();
        List<Recs.Pick> out = new ArrayList<>();
        for (String[] a : APPS) {
            if (out.size() >= max) break;
            if (installed(pm, a[0])) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = "app";
            p.id = a[0].hashCode();
            p.title = a[1];
            p.meta = a[2];
            p.pkg = PLAY_STORE;
            p.url = "market://details?id=" + a[0];
            p.backdrop = "asset:store/" + a[0] + ".jpg";
            p.detailed = true;
            out.add(p);
        }
        return out;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
