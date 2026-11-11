package com.harbour.tv;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.harbour.core.ChannelFolders;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Visible remote-first batch, group and personal-folder management. */
public final class ChannelLibraryActivity extends Activity {
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private PlaylistSubscriptions subscriptions;
  private FolderStore folderStore;
  private SourceManager sources;
  private JSONArray entries = new JSONArray(), batches = new JSONArray();
  private ChannelFolders folders = new ChannelFolders();
  private final Map<String, JSONObject> byId = new LinkedHashMap<>();
  private final Deque<Page> history = new ArrayDeque<>();
  private Page page = new Page("home", "", "");
  private boolean busy, returnAfterWork, homeAfterWork;
  private String incomingChannel;
  private LinearLayout root, actions;
  private TextView caption;
  private Button home;
  private static final int TEXT = 0xFFF4F7FC, MUTED = 0xFFA8BACB;
  private static final class Page {
    final String kind, id, group;
    Page(String kind, String id, String group) { this.kind=kind;this.id=id;this.group=group; }
  }
  private static final class Item {
    final String title, detail; final Runnable action;
    Item(String title,String detail,Runnable action){this.title=title;this.detail=detail;this.action=action;}
  }
  private interface Job { void run() throws Exception; }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    subscriptions = new PlaylistSubscriptions(this); folderStore = new FolderStore(this);
    sources = new SourceManager(this, this::reload);
    incomingChannel = getIntent().getStringExtra("channel_id");
    reload();
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);return t;}
  private Drawable background(boolean focused){GradientDrawable d=new GradientDrawable();d.setColor(focused?0xFF214F53:0xFF193044);d.setCornerRadius(dp(10));d.setStroke(dp(focused?2:1),focused?0xFF61E6CC:0xFF31485D);return d;}
  private Drawable selector(){StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_pressed},background(true));d.addState(new int[]{android.R.attr.state_focused},background(true));d.addState(new int[]{android.R.attr.state_selected},background(true));d.addState(new int[]{},background(false));return d;}
  private Button button(String name,Runnable action){Button b=new Button(this);b.setText(name);b.setTextColor(TEXT);b.setTextSize(15);b.setAllCaps(false);b.setMinHeight(dp(48));b.setBackground(selector());b.setOnClickListener(v->action.run());return b;}
  private void shell(String title,String help){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(18),dp(24),dp(18));root.setBackgroundColor(0xFF101F32);setContentView(root);
    LinearLayout header=new LinearLayout(this);root.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
    home=button("返回首頁",()->finishResult(true,null));header.addView(home,new LinearLayout.LayoutParams(dp(140),-1));
    TextView heading=text(title,24,TEXT);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(20),dp(8),0,0);header.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
    caption=text(help,14,MUTED);caption.setPadding(0,dp(12),0,dp(12));root.addView(caption);
    actions=new LinearLayout(this);root.addView(actions);
  }
  private void action(String label,Runnable run){Button b=button(label,run);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.setMargins(dp(3),0,dp(3),dp(8));actions.addView(b,lp);}
  private void list(List<Item> items){
    if(items.isEmpty()){TextView empty=text("這裡還沒有頻道。可匯入整份片單，或從現有頻道加入文件夾。",18,MUTED);empty.setPadding(dp(12),dp(24),0,0);root.addView(empty);if(actions.getChildCount()>0)actions.getChildAt(0).requestFocus();else home.requestFocus();return;}
    ListView lv=new ListView(this);lv.setDividerHeight(dp(5));lv.setSelector(selector());lv.setItemsCanFocus(false);
    root.addView(lv,new LinearLayout.LayoutParams(-1,0,1));
    lv.setAdapter(new BaseAdapter(){public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
      public View getView(int p,View old,ViewGroup parent){LinearLayout row;if(old instanceof LinearLayout)row=(LinearLayout)old;else{row=new LinearLayout(ChannelLibraryActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(16),dp(10),dp(16),dp(10));row.addView(text("",18,TEXT));row.addView(text("",13,MUTED));}
        Item x=items.get(p);((TextView)row.getChildAt(0)).setText(x.title);((TextView)row.getChildAt(1)).setText(x.detail);return row;}});
    lv.setOnItemClickListener((p,v,i,id)->items.get(i).action.run());lv.requestFocus();lv.setSelection(0);
  }
  private void navigate(Page next){history.push(page);page=next;render();}
  private List<JSONObject> rows(String batch,String group){List<JSONObject> out=new ArrayList<>();for(JSONObject row:byId.values())if((batch==null||batch.equals(row.optString("batchId")))&&(group==null||group.equals(PlaylistSubscriptions.groupOf(row))))out.add(row);return out;}
  private Set<String> ids(Collection<JSONObject> rows){Set<String> out=new LinkedHashSet<>();for(JSONObject r:rows)out.add(r.optString("id"));return out;}
  private JSONObject batch(String id){for(int i=0;i<batches.length();i++){JSONObject b=batches.optJSONObject(i);if(b!=null&&id.equals(b.optString("id")))return b;}return null;}
  private void render(){
    if(isFinishing()||isDestroyed())return;
    List<Item> items=new ArrayList<>();
    if(page.kind.equals("home")){
      shell("頻道與片單整理", "一次匯入整份 M3U，按原始 group-title 自動分組。文件夾只保存分類，不複製影片。");
      action("整份網址匯入",sources::openUrlImport);action("文件／文字匯入",sources::show);action("建立文件夾",()->editFolder(null));
      items.add(new Item("全部自備頻道 · "+byId.size()+" 個","選台後可播放、加入文件夾或刪除",()->navigate(new Page("all","",""))));
      for(int i=0;i<batches.length();i++){JSONObject b=batches.optJSONObject(i);if(b==null)continue;String id=b.optString("id");int n=rows(id,null).size();items.add(new Item(b.optString("name","匯入片單")+" · "+n+" 個", "來源訂閱 · 自動分組、更新、匯出、刪除",()->navigate(new Page("batch",id,""))));}
      if(!rows("",null).isEmpty())items.add(new Item("早期匯入／測試來源","未隸屬來源訂閱，仍可刪除及整理",()->navigate(new Page("group","",""))));
      for(ChannelFolders.Folder f:folders.all())items.add(new Item("文件夾 · "+f.name+"（"+f.members.size()+"）","自訂組合 · 移出文件夾不會刪除頻道",()->navigate(new Page("folder",f.id,""))));
    }else if(page.kind.equals("batch")){
      JSONObject b=batch(page.id);if(b==null){page=new Page("home","","");history.clear();render();return;}
      String id=page.id;List<JSONObject> batchRows=rows(id,null);
      shell(b.optString("name","來源訂閱"),batchRows.size()+" 個頻道 · 更新時間 "+b.optString("updatedAt","未記錄")+"\n按原始分組管理；可播放與否須逐來源驗證。");
      if(!b.optString("url").isEmpty())action("更新整份來源",()->sources.refreshSubscription(id));
      action("匯出整批",()->sources.exportIds(ids(batchRows)));
      action("刪除整批",()->confirm("刪除這份訂閱？","將移除本機 "+batchRows.size()+" 個頻道及其文件夾引用，不會刪除原始文件／遠端來源。",()->work("正在刪除訂閱…",()->folderStore.removeDeleted(subscriptions.deleteBatch(id)))));
      JSONArray excluded=b.optJSONArray("excluded");if(excluded!=null&&excluded.length()>0)action("恢復更新時排除項",()->confirm("恢復已排除頻道？","只清除排除紀錄；再次更新並確認後才會重新加入。",()->work("正在儲存…",()->subscriptions.restoreExclusions(id))));
      Map<String,Integer> counts=new LinkedHashMap<>();for(JSONObject r:batchRows)counts.merge(PlaylistSubscriptions.groupOf(r),1,Integer::sum);
      for(Map.Entry<String,Integer> group:counts.entrySet()){String name=group.getKey();items.add(new Item(name+" · "+group.getValue()+" 個","來源提供的 group-title",()->navigate(new Page("group",id,name))));}
    }else if(page.kind.equals("folder")){
      ChannelFolders.Folder f=folders.get(page.id);if(f==null){page=new Page("home","","");render();return;}
      String id=f.id;shell(f.name,"自訂文件夾 · 從這裡移出頻道，不會刪除原片單中的頻道。");
      action("加入／移出頻道",()->chooseMembers(id));action("重新命名",()->editFolder(id));
      action("刪除文件夾",()->confirm("刪除文件夾？","只刪除這個組合，保留所有原頻道。",()->work("正在儲存…",()->{ChannelFolders m=folderStore.load();m.delete(id);folderStore.save(m);} )));
      Set<String> members=new LinkedHashSet<>(f.members);action("匯出文件夾",()->sources.exportIds(members));
      for(String channel:f.members){JSONObject row=byId.get(channel);if(row!=null)items.add(channelItem(row,id));}
    }else{
      List<JSONObject> found=page.kind.equals("all")?rows(null,null):rows(page.id,page.group.isEmpty()?null:page.group);
      shell(page.kind.equals("all")?"全部自備頻道":(page.group.isEmpty()?"早期匯入／測試來源":page.group),found.size()+" 個頻道 · OK 開啟播放、文件夾與刪除操作。數量不代表可播放數量。");
      action("匯出此分組",()->sources.exportIds(ids(found)));
      action("刪除此組頻道",()->confirm("刪除此組已匯入頻道？","將刪除本機 "+found.size()+" 個頻道，而非僅移出文件夾。訂閱更新會保留刪除排除紀錄。",()->work("正在刪除頻道…",()->folderStore.removeDeleted(subscriptions.deleteIds(ids(found))))));
      for(JSONObject row:found)items.add(channelItem(row,null));
    }
    list(items);
  }
  private Item channelItem(JSONObject row,String folder){String id=row.optString("id");return new Item(row.optString("name","未命名頻道"),PlaylistSubscriptions.groupOf(row)+" · 按 OK 管理",()->channelActions(id,folder));}
  private void channelActions(String id,String folder){
    JSONObject row=byId.get(id);if(row==null){message("此條目是預設目錄或已刪除，尚無自備來源可整理。");return;}
    List<String> names=new ArrayList<>();List<Runnable> runs=new ArrayList<>();
    names.add("播放此頻道（檢查來源條件）");runs.add(()->finishResult(false,id));
    names.add("加入文件夾");runs.add(()->pickFolder(id));
    if(folder!=null){names.add("只從這個文件夾移除");runs.add(()->work("正在儲存…",()->{ChannelFolders m=folderStore.load();ChannelFolders.Folder f=m.get(folder);if(f!=null){f.members.remove(id);folderStore.save(m);}}));}
    names.add("刪除本機頻道");runs.add(()->confirm("刪除「"+row.optString("name")+"」？","會從本機片單及所有文件夾移除。這不是僅移出目前文件夾。原始來源不受影響。",()->work("正在刪除…",()->folderStore.removeDeleted(subscriptions.deleteIds(Collections.singleton(id))))));
    new AlertDialog.Builder(this).setTitle(row.optString("name")).setItems(names.toArray(new String[0]),(d,w)->runs.get(w).run()).setNegativeButton("返回",null).show();
  }
  private void editFolder(String id){
    EditText field=new EditText(this);field.setSingleLine(true);field.setHint("例如 家人最愛、新聞、運動");if(id!=null&&folders.get(id)!=null)field.setText(folders.get(id).name);
    AlertDialog d=new AlertDialog.Builder(this).setTitle(id==null?"建立文件夾":"重新命名文件夾").setView(field).setPositiveButton("儲存",null).setNegativeButton("取消",null).create();d.show();
    d.getButton(-1).setOnClickListener(v->{String name=field.getText().toString();try{ChannelFolders check=ChannelFolders.decode(folders.encode());if(id==null)check.create(name);else check.rename(id,name);d.dismiss();work("正在儲存…",()->{ChannelFolders actual=folderStore.load();if(id==null)actual.create(name);else actual.rename(id,name);folderStore.save(actual);});}catch(Exception e){field.setError(e.getMessage());}});
  }
  private void pickFolder(String channel){List<ChannelFolders.Folder> all=folders.all();if(all.isEmpty()){message("先在頻道整理首頁建立一個文件夾，再加入頻道。");return;}String[] names=new String[all.size()];for(int i=0;i<names.length;i++)names[i]=all.get(i).name;new AlertDialog.Builder(this).setTitle("加入哪個文件夾？").setItems(names,(d,w)->work("正在儲存…",()->{ChannelFolders m=folderStore.load();String id=all.get(w).id;ChannelFolders.Folder f=m.get(id);if(f!=null){Set<String> set=new LinkedHashSet<>(f.members);set.add(channel);m.setMembers(id,set);folderStore.save(m);}})).setNegativeButton("取消",null).show();}
  private void chooseMembers(String id){List<JSONObject> all=new ArrayList<>(byId.values());String[] names=new String[all.size()];boolean[] checked=new boolean[all.size()];Set<String> chosen=new LinkedHashSet<>(folders.get(id).members);for(int i=0;i<names.length;i++){JSONObject r=all.get(i);names[i]=r.optString("name")+" · "+PlaylistSubscriptions.groupOf(r);checked[i]=chosen.contains(r.optString("id"));}
    new AlertDialog.Builder(this).setTitle("文件夾內容 · 不會刪除頻道").setMultiChoiceItems(names,checked,(d,w,on)->{String channel=all.get(w).optString("id");if(on)chosen.add(channel);else chosen.remove(channel);}).setPositiveButton("儲存組合",(d,w)->work("正在儲存…",()->{ChannelFolders m=folderStore.load();m.setMembers(id,chosen);folderStore.save(m);})).setNegativeButton("取消",null).show();}
  private void confirm(String title,String body,Runnable yes){AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("確認",(x,w)->yes.run()).setNegativeButton("取消",null).create();d.show();d.getButton(-2).requestFocus();}
  private void message(String s){if(!isFinishing())new AlertDialog.Builder(this).setMessage(s).setPositiveButton("知道了",null).show();}
  private void reload(){work("正在讀取片單與分組…",()->{});}
  private void work(String label,Job job){
    if(busy||isFinishing())return;busy=true;shell("頻道整理",label+"\n按返回會在本次儲存完成後離開。");home.requestFocus();
    io.execute(()->{Exception error=null;JSONArray loadedEntries=null,loadedBatches=null;ChannelFolders loadedFolders=null;try{job.run();synchronized(PlaylistSubscriptions.class){loadedEntries=subscriptions.entries();loadedBatches=subscriptions.batches();}loadedFolders=folderStore.load();Set<String> valid=new HashSet<>();for(int i=0;i<loadedEntries.length();i++){JSONObject r=loadedEntries.optJSONObject(i);if(r!=null)valid.add(r.optString("id"));}boolean dirty=false;for(ChannelFolders.Folder f:loadedFolders.all())if(f.members.retainAll(valid))dirty=true;if(dirty)folderStore.save(loadedFolders);}catch(Exception e){error=e;}
      final Exception problem=error;final JSONArray ee=loadedEntries,bb=loadedBatches;final ChannelFolders ff=loadedFolders;
      runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;if(problem!=null){returnAfterWork=false;homeAfterWork=false;shell("頻道整理","操作未完成，請重新讀取確認本機資料。原始遠端來源不受影響。");action("重新讀取",this::reload);home.requestFocus();message(problem.getMessage()==null?"無法讀取或儲存資料":problem.getMessage());return;}if(returnAfterWork){finishResult(homeAfterWork,null);return;}entries=ee;batches=bb;folders=ff;byId.clear();for(int i=0;i<entries.length();i++){JSONObject r=entries.optJSONObject(i);if(r!=null)byId.put(r.optString("id"),r);}render();if(incomingChannel!=null){String id=incomingChannel;incomingChannel=null;channelActions(id,null);}});
    });
  }
  private void finishResult(boolean returnHome,String channel){if(busy){returnAfterWork=true;homeAfterWork=returnHome;caption.setText("正在儲存，完成後返回…");return;}Intent result=new Intent().putExtra("return_home",returnHome);if(channel!=null)result.putExtra("play_channel_id",channel);setResult(RESULT_OK,result);finish();}
  @Override public void onBackPressed(){if(busy){finishResult(false,null);return;}if(!history.isEmpty()){page=history.pop();render();}else finishResult(false,null);}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);sources.onActivityResult(request,result,data);}
  @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
