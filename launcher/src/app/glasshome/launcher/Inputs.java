package app.glasshome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * TV inputs (HDMI, AV, component) from Android's TV input framework, and switching to
 * one the way the stock launcher does: VIEW the input's passthrough "channel" so the TV app
 * shows it. Boxes without inputs (e.g. a Mi Box) report none, and the Inputs card and button hide.
 */
final class Inputs {
    static final String K_CARD = "inputs_card";

    private Inputs() {
    }

    static final class Input {
        String id, label;
        int type;
        boolean disconnected, usb;
        /** Given a name in the TV's input settings ("PS5"), so something is plugged in there. */
        boolean named;
    }

    /** File/media apps that can browse a USB drive, in order of preference. */
    static final String[] USB_APPS = {"com.droidlogic.mediacenter", "com.droidlogic.FileBrower", "com.yunfang.files",
            "nextapp.fx", "com.android.documentsui"};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("inputs", Context.MODE_PRIVATE);
    }

    static boolean showCard(Context c) {
        return prefs(c).getBoolean(K_CARD, true);
    }

    /**
     * Whether the TV has any inputs: once a list() has found some (Home reads it at start-up),
     * Control Centre opens without asking the TV input service again, as hardware inputs don't go
     * away. Only a yes is kept: just after a restart the TV may not have listed its ports yet.
     */
    static volatile boolean any;

    static boolean any(Context c) {
        return any || !list(c).isEmpty();
    }

    /** Hardware inputs in a natural order: HDMI first, then AV and the rest. */
    static List<Input> list(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tm;
        try {
            tm = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (Exception e) {
            return out;
        }
        if (tm == null) return out;
        List<TvInputInfo> infos;
        try {
            infos = tm.getTvInputList();
        } catch (Exception e) {
            return out;
        }
        for (TvInputInfo info : infos) {
            try {
                if (info.isHidden(c) || info.getParentId() != null) continue;
                if (!info.isPassthroughInput()) continue;  // not the tuner (Live TV): no aerial on this TV
                // Only the TV's own hardware (HDMI, AV): their ids end in "/HW<n>".
                // App-provided channel sources (Mi TV+, VLC previews...) are not inputs.
                if (!info.getId().contains("/HW")) continue;
                Input in = new Input();
                in.id = info.getId();
                in.type = info.getType();
                CharSequence custom = info.loadCustomLabel(c), label = info.loadLabel(c);
                in.named = custom != null && custom.length() > 0;
                in.label = in.named ? custom.toString() : label != null ? tidy(label.toString()) : "Input";
                in.disconnected = tm.getInputState(in.id) == TvInputManager.INPUT_STATE_DISCONNECTED;
                out.add(in);
            } catch (Exception ignored) {
                // skip inputs that can't be read
            }
        }
        // Named inputs (the PS5, the Sky box) first, then the plain ports in order (HDMI 1, 2...).
        out.sort((a, b) -> a.named != b.named ? (a.named ? -1 : 1)
                : Integer.compare(rank(a), rank(b)) != 0 ? Integer.compare(rank(a), rank(b))
                : a.label.compareToIgnoreCase(b.label));
        // A plugged-in USB drive shows as its own source (opens the TV's file or media app).
        if (!out.isEmpty()) {
            try {
                android.os.storage.StorageManager sm = c.getSystemService(android.os.storage.StorageManager.class);
                for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable() || !android.os.Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    Input in = new Input();
                    in.id = "usb:" + v.getUuid();
                    in.usb = true;
                    String d = v.getDescription(c);
                    in.label = d == null || d.isEmpty() ? "USB" : "USB \u00b7 " + d;
                    out.add(in);
                }
            } catch (Exception ignored) {
                // no storage info
            }
        }
        if (!out.isEmpty()) any = true;
        return out;
    }

    private static int rank(Input in) {
        switch (in.type) {
            case TvInputInfo.TYPE_HDMI: return 0;
            case TvInputInfo.TYPE_DISPLAY_PORT: return 1;
            case TvInputInfo.TYPE_COMPONENT: return 2;
            case TvInputInfo.TYPE_COMPOSITE: return 3;
            default: return 5;
        }
    }

    /** "HDMI1" -> "HDMI 1", "AV1" -> "AV 1". */
    private static String tidy(String s) {
        return s.replaceAll("^([A-Za-z]+)(\\d+)$", "$1 $2");
    }

    /** Glyph for an input: from its type, or its name when the type is generic (e.g. "USB", "AV"). */
    static String glyph(Input in) {
        if (in.usb) return "usb";
        // A games console on HDMI (named by the TV or over HDMI-CEC): a controller, not a plug.
        String name = in.label.toUpperCase(java.util.Locale.ROOT);
        for (String k : new String[]{"PS5", "PS4", "PLAYSTATION", "XBOX", "SWITCH", "NINTENDO", "CONSOLE", "GAME"}) {
            if (name.contains(k)) return "gamepad";
        }
        switch (in.type) {
            case TvInputInfo.TYPE_HDMI: return "hdmi";
            case TvInputInfo.TYPE_COMPOSITE: return "av";
            case TvInputInfo.TYPE_COMPONENT: return "component";
            case TvInputInfo.TYPE_SVIDEO: return "svideo";
            case TvInputInfo.TYPE_SCART: return "scart";
            case TvInputInfo.TYPE_VGA:
            case TvInputInfo.TYPE_DVI: return "vga";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "displayport";
            default: break;
        }
        String l = in.label.toUpperCase(java.util.Locale.ROOT);
        if (l.contains("USB") || l.contains("MEDIA")) return "usb";
        if (l.contains("HDMI")) return "hdmi";
        if (l.startsWith("AV") || l.contains("COMPOSITE")) return "av";
        if (l.contains("YPBPR") || l.contains("COMPONENT")) return "component";
        if (l.contains("VGA") || l.contains("PC")) return "vga";
        if (l.contains("SCART")) return "scart";
        if (l.contains("TV") || l.contains("DTV") || l.contains("ATV")) return "antenna";
        return "input";
    }

    /** Switches the TV to the input. Returns false when no app can show it. */
    static boolean open(Context c, Input in) {
        if (in.usb) {
            for (String pkg : USB_APPS) {
                Intent l = Suggestions.launchIntent(c, pkg);
                if (l != null) {
                    c.startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                }
            }
            return false;
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(in.id);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, TvContract.Channels.CONTENT_ITEM_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }
}
