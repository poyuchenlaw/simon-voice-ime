package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    public CommandsHelper(Context context) {
        this.context = context;
        this.groups = loadGroups();
        if (groups.isEmpty()) {
            initDefaults();
        }
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
        // 預設指令群組
        CommandGroup legal = new CommandGroup("法律");
        legal.commands.add(new Command("陳報狀開頭", "為陳報事項，茲依法陳報如下："));
        legal.commands.add(new Command("答辯狀開頭", "為答辯事項，答辯意旨如下："));
        legal.commands.add(new Command("聲請狀開頭", "為聲請事項，聲請意旨如下："));
        legal.commands.add(new Command("此致", "此致\n臺灣○○地方法院  公鑒"));

        CommandGroup daily = new CommandGroup("日常");
        daily.commands.add(new Command("地址", ""));
        daily.commands.add(new Command("Email", ""));
        daily.commands.add(new Command("電話", ""));

        CommandGroup symbols = new CommandGroup("符號");
        symbols.commands.add(new Command("全形括號", "（）"));
        symbols.commands.add(new Command("書名號", "《》"));
        symbols.commands.add(new Command("引號", "「」"));
        symbols.commands.add(new Command("破折號", "——"));
        symbols.commands.add(new Command("項次 一", "一、"));
        symbols.commands.add(new Command("項次 (一)", "（一）"));
        symbols.commands.add(new Command("項次 1.", "1."));

        groups.add(legal);
        groups.add(daily);
        groups.add(symbols);
        save();
    }

    private void save() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATA, toJson().toString())
                .apply();
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
