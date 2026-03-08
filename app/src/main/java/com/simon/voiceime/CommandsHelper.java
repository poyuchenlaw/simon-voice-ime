package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 常用指令管理 — 支援分組，可自訂
 *
 * 資料結構：
 * {
 *   "groups": [
 *     {
 *       "name": "法律",
 *       "commands": [
 *         {"label": "陳報狀開頭", "text": "為陳報事項，茲依法陳報如下："},
 *         {"label": "答辯狀開頭", "text": "為答辯事項，答辯意旨如下："}
 *       ]
 *     }
 *   ]
 * }
 */
public class CommandsHelper {

    private static final String PREFS_NAME = "simon_ime_commands";
    private static final String KEY_DATA = "commands_data";

    private final Context context;
    private List<CommandGroup> groups;

    public static class Command {
        public String label;
        public String text;

        public Command(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }

    public static class CommandGroup {
        public String name;
        public List<Command> commands;

        public CommandGroup(String name) {
            this.name = name;
            this.commands = new ArrayList<>();
        }
    }

    private static final int CURRENT_SCHEMA_VERSION = 2;  // bump to trigger migration

    private static final String TAG = "CommandsHelper";
    private static final String BACKUP_FILENAME = "commands_backup.json";

    public CommandsHelper(Context context) {
        this.context = context;
        this.groups = loadGroups();
        if (groups.isEmpty()) {
            // Try restoring from file backup before falling back to defaults
            List<CommandGroup> restored = loadFromFileBackup();
            if (!restored.isEmpty()) {
                Log.i(TAG, "Restored " + restored.size() + " groups from file backup");
                this.groups = restored;
                save(); // re-save to SharedPreferences
            } else {
                initDefaults();
            }
        } else {
            // Check if we need to migrate (add new default groups)
            int savedVersion = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("schema_version", 1);
            if (savedVersion < CURRENT_SCHEMA_VERSION) {
                migrateDefaults();
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt("schema_version", CURRENT_SCHEMA_VERSION).apply();
            }
        }
    }

    /** Add new default groups without overwriting existing ones */
    private void migrateDefaults() {
        List<CommandGroup> defaults = new ArrayList<>();
        // Build the same defaults list
        CommandsHelper tmp = new CommandsHelper(context, true); // skip constructor logic
        tmp.initDefaults();
        for (CommandGroup dg : tmp.groups) {
            boolean exists = false;
            for (CommandGroup eg : this.groups) {
                if (eg.name.equals(dg.name)) { exists = true; break; }
            }
            if (!exists) {
                this.groups.add(dg);
            }
        }
        save();
    }

    /** Internal constructor for migration (no load/save) */
    private CommandsHelper(Context context, boolean skipLoad) {
        this.context = context;
        this.groups = new ArrayList<>();
    }

    public List<CommandGroup> getGroups() {
        return groups;
    }

    public List<String> getGroupNames() {
        List<String> names = new ArrayList<>();
        for (CommandGroup g : groups) {
            names.add(g.name);
        }
        return names;
    }

    public List<Command> getCommands(String groupName) {
        for (CommandGroup g : groups) {
            if (g.name.equals(groupName)) {
                return g.commands;
            }
        }
        return new ArrayList<>();
    }

    public void addGroup(String name) {
        for (CommandGroup g : groups) {
            if (g.name.equals(name)) return;
        }
        groups.add(new CommandGroup(name));
        save();
    }

    public void addCommand(String groupName, String label, String text) {
        for (CommandGroup g : groups) {
            if (g.name.equals(groupName)) {
                g.commands.add(new Command(label, text));
                save();
                return;
            }
        }
        // Create group if not exists
        CommandGroup newGroup = new CommandGroup(groupName);
        newGroup.commands.add(new Command(label, text));
        groups.add(newGroup);
        save();
    }

    public void removeCommand(String groupName, int index) {
        for (CommandGroup g : groups) {
            if (g.name.equals(groupName) && index >= 0 && index < g.commands.size()) {
                g.commands.remove(index);
                save();
                return;
            }
        }
    }

    public void updateCommand(String groupName, int index, String newLabel, String newText) {
        for (CommandGroup g : groups) {
            if (g.name.equals(groupName) && index >= 0 && index < g.commands.size()) {
                Command cmd = g.commands.get(index);
                cmd.label = newLabel;
                cmd.text = newText;
                save();
                return;
            }
        }
    }

    public void renameGroup(String oldName, String newName) {
        for (CommandGroup g : groups) {
            if (g.name.equals(oldName)) {
                g.name = newName;
                save();
                return;
            }
        }
    }

    public void moveCommand(String groupName, int fromIndex, int toIndex) {
        for (CommandGroup g : groups) {
            if (g.name.equals(groupName)) {
                if (fromIndex >= 0 && fromIndex < g.commands.size()
                        && toIndex >= 0 && toIndex < g.commands.size()) {
                    Command cmd = g.commands.remove(fromIndex);
                    g.commands.add(toIndex, cmd);
                    save();
                }
                return;
            }
        }
    }

    public void removeGroup(String groupName) {
        groups.removeIf(g -> g.name.equals(groupName));
        save();
    }

    /** Import commands from JSON string (for settings sync) */
    public boolean importFromJson(String json) {
        try {
            groups = parseGroups(new JSONObject(json));
            save();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Export commands as JSON string */
    public String exportToJson() {
        return toJson().toString();
    }

    private void initDefaults() {
        // === 書狀格式 ===
        CommandGroup brief = new CommandGroup("書狀格式");
        brief.commands.add(new Command("陳報狀開頭", "為陳報事項，茲依法陳報如下："));
        brief.commands.add(new Command("答辯狀開頭", "為答辯事項，答辯意旨如下："));
        brief.commands.add(new Command("聲請狀開頭", "為聲請事項，聲請意旨如下："));
        brief.commands.add(new Command("此致", "此致\n臺灣臺北地方法院民事庭　公鑒"));
        brief.commands.add(new Command("懇請鈞院鑒核", "懇請鈞院鑒核，賜判如訴之聲明，以維權益。"));
        brief.commands.add(new Command("鈞院", "鈞院"));
        brief.commands.add(new Command("謹狀", "謹　狀"));
        brief.commands.add(new Command("具狀人", "具　狀　人："));
        brief.commands.add(new Command("訴訟代理人", "訴訟代理人："));
        brief.commands.add(new Command("書狀結尾", "此致\n臺灣臺北地方法院\n\n具狀人：\n\n中華民國     年     月     日"));
        brief.commands.add(new Command("開庭問候", "審判長、受命法官、各位法官好，我是原告訴訟代理人，廣信法律事務所陳柏諭律師。"));

        // === 法律術語 ===
        CommandGroup terms = new CommandGroup("法律術語");
        terms.commands.add(new Command("按", "按...規定，"));
        terms.commands.add(new Command("次按", "次按"));
        terms.commands.add(new Command("末按", "末按"));
        terms.commands.add(new Command("惟查", "惟查"));
        terms.commands.add(new Command("核先敘明", "核先敘明，"));
        terms.commands.add(new Command("爰依", "爰依"));
        terms.commands.add(new Command("綜上所述", "綜上所述，"));
        terms.commands.add(new Command("茲", "茲"));
        terms.commands.add(new Command("故", "故"));
        terms.commands.add(new Command("倘若", "倘若"));
        terms.commands.add(new Command("致", "致"));

        // === 法律句型 ===
        CommandGroup patterns = new CommandGroup("法律句型");
        patterns.commands.add(new Command("顯不足採", "顯不足採。"));
        patterns.commands.add(new Command("定有明文", "定有明文。"));
        patterns.commands.add(new Command("實屬無益", "實屬無益。"));
        patterns.commands.add(new Command("判決意旨參照", "判決意旨參照。"));
        patterns.commands.add(new Command("相當因果關係", "與...行為間，具有相當因果關係。"));
        patterns.commands.add(new Command("法定無過失責任", "物之瑕疵擔保責任係法定無過失責任，凡買賣標的物於危險移轉時存有瑕疵，不問出賣人對該瑕疵之存在是否知情、是否有過失，均應負責。"));
        patterns.commands.add(new Command("民法184", "因故意或過失，不法侵害他人之權利者，負損害賠償責任。故意以背於善良風俗之方法，加損害於他人者亦同。違反保護他人之法律，致生損害於他人者，負賠償責任。但能證明其行為無過失者，不在此限。"));
        patterns.commands.add(new Command("民法767", "所有人對於無權占有或侵奪其所有物者，得請求返還之。對於妨害其所有權者，得請求除去之。有妨害其所有權之虞者，得請求防止之。"));

        // === 攻防用語 ===
        CommandGroup attack = new CommandGroup("攻防用語");
        attack.commands.add(new Command("被告空言抗辯", "被告空言抗辯，未提出任何證據，顯不足採。"));
        attack.commands.add(new Command("倒果為因", "係倒果為因之謬誤論述"));
        attack.commands.add(new Command("被告云云", "被告...云云，實屬無益。"));

        // === 爭點論述 ===
        CommandGroup issues = new CommandGroup("爭點論述");
        issues.commands.add(new Command("爭執事項", "爭執事項（聚焦）"));
        issues.commands.add(new Command("不爭執事項", "不爭執事項（補充）"));
        issues.commands.add(new Command("舉證責任", "舉證責任"));
        issues.commands.add(new Command("附隨義務", "契約之附隨義務"));
        issues.commands.add(new Command("誠信原則", "誠信原則"));

        // === 符號 ===
        CommandGroup symbols = new CommandGroup("符號");
        symbols.commands.add(new Command("全形括號", "（）"));
        symbols.commands.add(new Command("書名號", "《》"));
        symbols.commands.add(new Command("引號", "「」"));
        symbols.commands.add(new Command("破折號", "——"));
        symbols.commands.add(new Command("項次 一", "一、"));
        symbols.commands.add(new Command("項次 (一)", "（一）"));
        symbols.commands.add(new Command("項次 1.", "1."));

        // === 日常 ===
        CommandGroup daily = new CommandGroup("日常");
        daily.commands.add(new Command("地址", ""));
        daily.commands.add(new Command("Email", ""));
        daily.commands.add(new Command("電話", ""));

        groups.add(brief);
        groups.add(terms);
        groups.add(patterns);
        groups.add(attack);
        groups.add(issues);
        groups.add(symbols);
        groups.add(daily);
        save();
    }

    private void save() {
        String json = toJson().toString();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATA, json)
                .apply();
        saveToFileBackup(json);
    }

    private void saveToFileBackup(String json) {
        try {
            File backup = new File(context.getFilesDir(), BACKUP_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(backup)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save file backup", e);
        }
    }

    private List<CommandGroup> loadFromFileBackup() {
        File backup = new File(context.getFilesDir(), BACKUP_FILENAME);
        if (!backup.exists()) return new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(backup)) {
            byte[] data = new byte[(int) backup.length()];
            fis.read(data);
            String json = new String(data, StandardCharsets.UTF_8);
            return parseGroups(new JSONObject(json));
        } catch (Exception e) {
            Log.w(TAG, "Failed to load file backup", e);
            return new ArrayList<>();
        }
    }

    private JSONObject toJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray groupsArr = new JSONArray();
            for (CommandGroup g : groups) {
                JSONObject go = new JSONObject();
                go.put("name", g.name);
                JSONArray cmds = new JSONArray();
                for (Command c : g.commands) {
                    JSONObject co = new JSONObject();
                    co.put("label", c.label);
                    co.put("text", c.text);
                    cmds.put(co);
                }
                go.put("commands", cmds);
                groupsArr.put(go);
            }
            root.put("groups", groupsArr);
            return root;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private List<CommandGroup> loadGroups() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_DATA, "{}");
        try {
            return parseGroups(new JSONObject(json));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    private List<CommandGroup> parseGroups(JSONObject root) throws JSONException {
        List<CommandGroup> result = new ArrayList<>();
        JSONArray groupsArr = root.optJSONArray("groups");
        if (groupsArr == null) return result;

        for (int i = 0; i < groupsArr.length(); i++) {
            JSONObject go = groupsArr.getJSONObject(i);
            CommandGroup g = new CommandGroup(go.getString("name"));
            JSONArray cmds = go.optJSONArray("commands");
            if (cmds != null) {
                for (int j = 0; j < cmds.length(); j++) {
                    JSONObject co = cmds.getJSONObject(j);
                    g.commands.add(new Command(co.getString("label"), co.getString("text")));
                }
            }
            result.add(g);
        }
        return result;
    }
}
