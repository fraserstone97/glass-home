package app.glasshome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where Home keeps its own settings (layout, background, idle time, top shelf). Profiles are off
 * on this TV, but Home still reads the settings of the profile that was current when they were
 * on, so nothing set then is lost. The first profile ("p1") uses the original storage.
 */
final class Profiles {
    private Profiles() { }

    /** The current profile's id: the saved choice while it is still in the list, else the first one. */
    static String currentId(Context c) {
        SharedPreferences store = c.getSharedPreferences("profiles", Context.MODE_PRIVATE);
        List<String> ids = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(store.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.getString("id");
                o.getString("name");  // a profile saved without a name ends the list, as it always did
                ids.add(id);
            }
        } catch (Exception ignored) {
        }
        if (ids.isEmpty()) ids.add("p1");
        String id = store.getString("current", "p1");
        return ids.contains(id) ? id : ids.get(0);
    }

    static String prefsName(String id) {
        return id.equals("p1") ? "home" : "home_" + id;
    }

    /** Layout/background preferences of the current profile. */
    static SharedPreferences homePrefs(Context c) {
        return c.getSharedPreferences(prefsName(currentId(c)), Context.MODE_PRIVATE);
    }
}
