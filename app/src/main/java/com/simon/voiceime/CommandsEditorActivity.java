package com.simon.voiceime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 常用指令編輯器 — 完整 CRUD + 拖拽排序
 */
public class CommandsEditorActivity extends Activity {

    private CommandsHelper commandsHelper;
    private String currentGroup = null;
    private RecyclerView recycler;
    private LinearLayout tabContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_commands_editor);

        commandsHelper = new CommandsHelper(this);
        tabContainer = findViewById(R.id.editorGroupTabs);
        recycler = findViewById(R.id.editorCmdRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        // 拖拽排序
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int fromPos = from.getAdapterPosition();
                int toPos = to.getAdapterPosition();
                if (currentGroup != null) {
                    commandsHelper.moveCommand(currentGroup, fromPos, toPos);
                    rv.getAdapter().notifyItemMoved(fromPos, toPos);
                }
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
        });
        touchHelper.attachToRecyclerView(recycler);

        // 新增群組
        findViewById(R.id.btnAddGroup).setOnClickListener(v -> showAddGroupDialog());

        // 改名群組
        findViewById(R.id.btnRenameGroup).setOnClickListener(v -> {
            if (currentGroup != null) showRenameGroupDialog();
        });

        // 刪除群組
        findViewById(R.id.btnDeleteGroup).setOnClickListener(v -> {
            if (currentGroup != null) showDeleteGroupDialog();
        });

        // 新增指令
        findViewById(R.id.btnAddCommand).setOnClickListener(v -> {
            if (currentGroup != null) showEditCommandDialog(-1, "", "");
        });

        refreshAll();
    }

    private void refreshAll() {
        List<String> names = commandsHelper.getGroupNames();

        // 選取群組
        if (names.isEmpty()) {
            currentGroup = null;
        } else if (currentGroup == null || !names.contains(currentGroup)) {
            currentGroup = names.get(0);
        }

        // 群組 tab
        tabContainer.removeAllViews();
        for (String name : names) {
            Button tab = new Button(this);
            tab.setText(name);
            tab.setTextSize(13);
            tab.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            tab.setLayoutParams(lp);
            tab.setPadding(20, 8, 20, 8);

            if (name.equals(currentGroup)) {
                tab.setTextColor(0xFF4ECCA3);
                tab.setBackgroundColor(0xFF1a1a2e);
            } else {
                tab.setTextColor(0xFF888888);
                tab.setBackgroundColor(0xFF16213e);
            }

            tab.setOnClickListener(v -> {
                currentGroup = name;
                refreshAll();
            });
            tabContainer.addView(tab);
        }

        // 指令列表
        if (currentGroup != null) {
            List<CommandsHelper.Command> cmds = commandsHelper.getCommands(currentGroup);
            recycler.setAdapter(new EditorCmdAdapter(cmds));
        } else {
            recycler.setAdapter(null);
        }
    }

    // ==================== Adapter ====================

    private class EditorCmdAdapter extends RecyclerView.Adapter<EditorCmdAdapter.VH> {
        private final List<CommandsHelper.Command> items;

        EditorCmdAdapter(List<CommandsHelper.Command> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.editor_cmd_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CommandsHelper.Command cmd = items.get(position);
            holder.label.setText(cmd.label);
            holder.text.setText(cmd.text);

            holder.btnEdit.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < items.size()) {
                    CommandsHelper.Command c = items.get(pos);
                    showEditCommandDialog(pos, c.label, c.text);
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < items.size()) {
                    new AlertDialog.Builder(CommandsEditorActivity.this)
                            .setTitle("刪除指令")
                            .setMessage("確定刪除「" + items.get(pos).label + "」？")
                            .setPositiveButton("刪除", (d, w) -> {
                                commandsHelper.removeCommand(currentGroup, pos);
                                refreshAll();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView label, text;
            Button btnEdit, btnDelete;

            VH(View v) {
                super(v);
                label = v.findViewById(R.id.editorCmdLabel);
                text = v.findViewById(R.id.editorCmdText);
                btnEdit = v.findViewById(R.id.btnEditCmd);
                btnDelete = v.findViewById(R.id.btnDeleteCmd);
            }
        }
    }

    // ==================== Dialogs ====================

    private void showAddGroupDialog() {
        EditText input = new EditText(this);
        input.setHint("群組名稱");
        input.setTextColor(0xFFe0e0e0);
        input.setHintTextColor(0xFF666666);
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle("新增群組")
                .setView(input)
                .setPositiveButton("新增", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        commandsHelper.addGroup(name);
                        currentGroup = name;
                        refreshAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameGroupDialog() {
        EditText input = new EditText(this);
        input.setText(currentGroup);
        input.setTextColor(0xFFe0e0e0);
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle("群組改名")
                .setView(input)
                .setPositiveButton("確定", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(currentGroup)) {
                        commandsHelper.renameGroup(currentGroup, newName);
                        currentGroup = newName;
                        refreshAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteGroupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("刪除群組")
                .setMessage("確定刪除「" + currentGroup + "」及其所有指令？")
                .setPositiveButton("刪除", (d, w) -> {
                    commandsHelper.removeGroup(currentGroup);
                    currentGroup = null;
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEditCommandDialog(int index, String oldLabel, String oldText) {
        View dialogView = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_2, null);
        // Use a custom LinearLayout instead
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 0);

        EditText inputLabel = new EditText(this);
        inputLabel.setHint("標籤（如：陳報狀開頭）");
        inputLabel.setText(oldLabel);
        inputLabel.setTextColor(0xFFe0e0e0);
        inputLabel.setHintTextColor(0xFF666666);

        EditText inputText = new EditText(this);
        inputText.setHint("指令內容");
        inputText.setText(oldText);
        inputText.setTextColor(0xFFe0e0e0);
        inputText.setHintTextColor(0xFF666666);
        inputText.setMinLines(3);

        layout.addView(inputLabel);
        layout.addView(inputText);

        String title = index < 0 ? "新增指令" : "編輯指令";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("確定", (d, w) -> {
                    String label = inputLabel.getText().toString().trim();
                    String text = inputText.getText().toString();
                    if (label.isEmpty()) {
                        Toast.makeText(this, "標籤不能為空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (index < 0) {
                        commandsHelper.addCommand(currentGroup, label, text);
                    } else {
                        commandsHelper.updateCommand(currentGroup, index, label, text);
                    }
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
