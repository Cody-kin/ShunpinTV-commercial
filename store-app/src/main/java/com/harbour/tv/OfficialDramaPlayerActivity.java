package com.harbour.tv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.*;
import android.widget.*;

/** Original official webpage only: no JS bridge, stream extraction, signatures or DRM handling. */
public final class OfficialDramaPlayerActivity extends Activity {
    private LinearLayout root, toolbar;
    private FrameLayout host;
    private TextView status;
    private Button enter;
    private WebView web;
    private String page;
    private boolean visited;
    private View fullVideo;
    private WebChromeClient.CustomViewCallback fullCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
    if(!BuildConfig.PERSONAL_BUILD){finish();return;}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        toolbar=new LinearLayout(this);toolbar.setPadding(16,8,16,8);
        toolbar.addView(action("返回選集",this::finish));
        enter=action("操作官方頁",() -> { if(web!=null)web.requestFocus(); });toolbar.addView(enter);
        toolbar.addView(action("重新載入",() -> { if(web!=null)web.loadUrl(page); }));
        root.addView(toolbar);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setPadding(20,8,20,8);
        status.setText("官方原頁 · 請在頁內操作播放；選單鍵回到工具列。未驗證可播放。");root.addView(status);
        host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        try {page=PublicDramaParser.checkedPage(state==null?getIntent().getStringExtra("page"):state.getString("page"));}
        catch(Exception invalid){status.setText("官方頁網址不符合允許範圍，未載入。");}
        enter.requestFocus();
    }
    private Button action(String title,Runnable run) {Button b=new Button(this);b.setText(title);b.setOnClickListener(v -> run.run());return b;}
    private boolean allowed(String url) {
        try { return PublicDramaParser.seriesId(page).equals(PublicDramaParser.seriesId(url)); }
        catch(Exception invalid){return false;}
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void createPage() {
        if(page==null || web!=null)return;
        try {web=new WebView(this);}catch(RuntimeException unavailable){status.setText("此裝置 WebView 無法啟動。請更新系統 WebView 後重試。");return;}
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!r.isForMainFrame())return false;
                if(allowed(r.getUrl().toString()))return false;
                status.setText("官網要求跳轉至目前未支援的頁面／登入流程；未繞過限制。");return true;
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                if(!allowed(url)){v.stopLoading();status.setText("已停止未支援的官方頁跳轉。返回選集可重新選擇。");}
            }
            @Override public void onPageFinished(WebView v,String url){if(allowed(url))page=PublicDramaParser.checkedPage(url);}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError error){if(r.isForMainFrame())status.setText("官方頁載入失敗。可重新載入或返回選集；不代表內容已可播放。");}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse response){if(r.isForMainFrame())status.setText("官方頁回應 HTTP "+response.getStatusCode()+"。未繞過存取或地域限制。");}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){releasePage();status.setText("WebView 播放程序已停止；返回選集後可重開。");return true;}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();status.setText("官方頁要求額外權限，目前未提供；受保護內容可能無法播放。");}
            @Override public void onShowCustomView(View view,CustomViewCallback callback){
                if(fullVideo!=null){callback.onCustomViewHidden();return;}
                fullVideo=view;fullCallback=callback;host.addView(view,new FrameLayout.LayoutParams(-1,-1));
                toolbar.setVisibility(View.GONE);status.setVisibility(View.GONE);view.requestFocus();
            }
            @Override public void onHideCustomView(){exitFullscreen();}
        });
        host.addView(web,new FrameLayout.LayoutParams(-1,-1));web.loadUrl(page);
        if(visited)status.setText("已返回官方頁；請自行繼續播放。未自動恢復影片秒數或播放。");
        visited=true;
    }
    private void exitFullscreen(){
        if(fullVideo==null)return;host.removeView(fullVideo);fullVideo=null;
        WebChromeClient.CustomViewCallback callback=fullCallback;fullCallback=null;
        if(callback!=null)callback.onCustomViewHidden();toolbar.setVisibility(View.VISIBLE);status.setVisibility(View.VISIBLE);enter.requestFocus();
    }
    private void releasePage(){
        exitFullscreen();WebView old=web;web=null;
        if(old!=null){old.onPause();old.stopLoading();host.removeView(old);old.destroy();}
    }
    @Override protected void onStart(){super.onStart();createPage();}
    @Override protected void onStop(){releasePage();super.onStop();}
    @Override protected void onDestroy(){releasePage();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("page",page);super.onSaveInstanceState(state);}
    @Override public void onBackPressed(){if(fullVideo!=null)exitFullscreen();else finish();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP){exitFullscreen();enter.requestFocus();}return true;}
        return super.dispatchKeyEvent(event);
    }
}
