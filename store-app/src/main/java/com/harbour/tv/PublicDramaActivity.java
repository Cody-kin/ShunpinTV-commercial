package com.harbour.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.harbour.core.PublicDrama;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public metadata browser with explicit, unmodified official-page playback. */
public final class PublicDramaActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status; private ListView rows; private Button load, cancel;
    private EditText input; private PublicDrama shown; private String selectedEpisode;
    private boolean stopped;
    private PublicDramaClient.Request current;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
    if(!BuildConfig.PERSONAL_BUILD){finish();return;}
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 24, 40, 24); layout.setBackgroundColor(Color.rgb(10, 20, 36));
        layout.addView(label("紅果公開劇集", 27));
        layout.addView(label("貼上官方 HTTPS 詳情／播放頁。只讀公開資料，不登入、不解鎖；可在 App 內開啟官方播放頁；能否播放由官網決定。", 16));
        input = new EditText(this); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY); input.setHint("https://hongguoduanju.com/detail?series_id=…");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null) input.setText(shared);
        if (state != null) { input.setText(state.getString("input", "")); selectedEpisode=state.getString("selected"); }
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        load = new Button(this); load.setText("讀取公開劇集"); layout.addView(load);
        cancel = new Button(this); cancel.setText("取消讀取"); cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(v -> { cancelCurrent(); end("已取消讀取；保留原有目錄。"); }); layout.addView(cancel);
        status = label("尚未讀取。目錄數量不等於可播放數量。", 16); layout.addView(status);
        rows = new ListView(this);
        rows.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if(shown!=null && position>=0 && position<shown.episodeIds.size()) selectedEpisode=shown.episodeIds.get(position);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        layout.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = new Button(this); back.setText("返回"); back.setOnClickListener(v -> finish()); layout.addView(back);
        setContentView(layout); input.requestFocus();
        if (state != null && state.getStringArrayList("episodes") != null) {
            show(new PublicDrama(state.getString("series"),state.getString("title"),state.getInt("reported",-1),state.getInt("accessible",-1),state.getStringArrayList("episodes")));
        }
        load.setOnClickListener(v -> {
            if (current != null) return;
            final String page;
            try { page = PublicDramaParser.checkedPage(input.getText().toString()); }
            catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
            PublicDramaClient.Request request = begin("正在讀取官方公開 HTML…");
            worker.execute(() -> {
                try {
                    PublicDrama drama = PublicDramaParser.parse(PublicDramaClient.read(page, request), page);
                    ui(request, () -> show(drama));
                } catch (Exception e) { ui(request, () -> end("讀取失敗：" + safeError(e))); }
            });
        });
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextColor(Color.WHITE);
        view.setTextSize(size); view.setPadding(0, 5, 0, 5); return view;
    }
    static String countLabel(int count) { return count < 0 ? "未知" : String.valueOf(count); }
    private void show(PublicDrama drama) {
        String restoreId = selectedEpisode;
        shown = drama;
        end(drama.title + "\n已列出 " + drama.episodeIds.size() + " 集；官網報告總數 " + countLabel(drama.reportedEpisodes)
                + "／可訪問 " + countLabel(drama.pageAccessibleEpisodes) + "。已驗證可播放：0。");
        Map<Integer, String> checked = new HashMap<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return drama.episodeIds.size(); }
            @Override public Object getItem(int position) { return drama.episodeIds.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = recycled instanceof TextView ? (TextView) recycled : label("", 19);
                row.setPadding(16, 18, 16, 18); row.setMinHeight(64);
                row.setText("第 " + (position + 1) + " 集 · " + checked.getOrDefault(position, "檢查公開狀態"));
                return row;
            }
        };
        rows.setAdapter(adapter);
        rows.setOnItemClickListener((parent, view, position, itemId) -> {
            if (current != null) return;
            selectedEpisode = drama.episodeIds.get(position);
            new AlertDialog.Builder(this).setTitle("第 " + (position + 1) + " 集")
                .setItems(new String[]{"在 App 內開啟官方播放頁", "檢查公開狀態"}, (dialog, choice) -> {
                    if (choice == 0) {
                        startActivity(new Intent(this, OfficialDramaPlayerActivity.class).putExtra("page", PublicDramaParser.episodePage(drama.seriesId, selectedEpisode)));
                    } else checkEpisode(drama, position, checked, adapter);
                }).setNegativeButton("返回", null).show();
        });
        rows.requestFocus(); rows.setSelection(drama.indexOfEpisode(restoreId));
    }
    private void checkEpisode(PublicDrama drama, int position, Map<Integer,String> checked, BaseAdapter adapter) {
            if (current != null) return;
            String id = drama.episodeIds.get(position);
            PublicDramaClient.Request request = begin("正在檢查第 " + (position + 1) + " 集公開頁面…");
            worker.execute(() -> {
                try {
                    String html = PublicDramaClient.read(PublicDramaParser.episodePage(drama.seriesId, id), request);
                    PublicDrama.Assessment result = PublicDramaParser.assess(html, drama.seriesId, id);
                    ui(request, () -> { checked.put(position, result.reason); adapter.notifyDataSetChanged(); end(result.reason); });
                } catch (Exception e) { ui(request, () -> end("檢查失敗：" + safeError(e))); }
            });
    }
    private PublicDramaClient.Request begin(String message) {
        current = new PublicDramaClient.Request(); load.setEnabled(false); cancel.setVisibility(View.VISIBLE); status.setText(message);
        return current;
    }
    private void end(String message) {
        current = null; load.setEnabled(true); cancel.setVisibility(View.GONE); status.setText(message);
    }
    private void cancelCurrent() { if (current != null) current.cancel(); current = null; }
    private void ui(PublicDramaClient.Request request, Runnable action) {
        runOnUiThread(() -> { if (!stopped && !isFinishing() && !isDestroyed() && current == request) action.run(); });
    }
    private static String safeError(Exception e) {
        if (e instanceof IllegalArgumentException) return e.getMessage();
        return "請稍後重試（" + e.getClass().getSimpleName() + "）";
    }
    @Override protected void onStart() { super.onStart(); stopped=false; }
    @Override protected void onResume() { super.onResume(); if(shown!=null) { final int index=shown.indexOfEpisode(selectedEpisode); rows.post(() -> { rows.requestFocus(); rows.setSelection(index); }); } }
    @Override protected void onStop() {
        stopped=true;
        if(current!=null) { cancelCurrent(); end("已停止背景讀取；返回後可重新讀取，原有目錄保留。"); }
        super.onStop();
    }
    @Override public void onBackPressed() {
        if(current!=null) { cancelCurrent(); end("已取消讀取；再按返回離開。"); load.requestFocus(); }
        else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("input",input.getText().toString()); state.putString("selected",selectedEpisode);
        if(shown!=null && shown.episodeIds.size()<=1000) {
            state.putString("series",shown.seriesId);state.putString("title",shown.title);
            state.putInt("reported",shown.reportedEpisodes);state.putInt("accessible",shown.pageAccessibleEpisodes);
            state.putStringArrayList("episodes",new java.util.ArrayList<>(shown.episodeIds));
        }
        super.onSaveInstanceState(state);
    }
    @Override public void onDestroy() { cancelCurrent(); worker.shutdownNow(); super.onDestroy(); }
}
