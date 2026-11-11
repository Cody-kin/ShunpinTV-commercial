package com.harbour.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic fake connections: no network requests or media access. */
public final class HttpsPlaylistFetcherTests {
  static int checks;
  static final class Fake extends HttpURLConnection {
    final int status; final String location; final byte[] body;
    boolean disconnected, read; long length = -1;
    Fake(String url, int status, String location, String body) throws Exception {
      super(new URL(url)); this.status=status; this.location=location;
      this.body=body.getBytes(StandardCharsets.UTF_8);
    }
    public void connect() {} public boolean usingProxy(){return false;}
    public void disconnect(){disconnected=true;}
    public int getResponseCode(){return status;}
    public String getHeaderField(String name){return "Location".equals(name)?location:null;}
    public long getContentLengthLong(){return length;}
    public InputStream getInputStream(){read=true;return new ByteArrayInputStream(body);}
  }
  static final class Route implements HttpsPlaylistFetcher.ConnectionFactory {
    final Map<String,Fake> map=new LinkedHashMap<>(); final List<String> opened=new ArrayList<>();
    Route add(String url,int status,String location,String body)throws Exception{map.put(url,new Fake(url,status,location,body));return this;}
    public HttpURLConnection open(URL url)throws IOException {
      opened.add(url.toString()); Fake f=map.get(url.toString());
      if(f==null)throw new IOException("secret-token="+url);return f;
    }
  }
  static void check(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
  static void reject(String url,Route route,String expected)throws Exception {
    try {HttpsPlaylistFetcher.fetch(url,route);throw new AssertionError("accepted "+expected);}
    catch(HttpsPlaylistFetcher.DownloadException failure){
      check(failure.getMessage().contains(expected),"specific safe error: "+expected);
      check(!failure.toString().contains("secret")&&failure.getCause()==null,"no token/cause leakage");
    }
  }
  public static void main(String[] args)throws Exception {
    String start="https://origin.example/start?secret=token", target="https://cdn.example/lists/index.m3u";
    Route valid=new Route().add(start,302,target,"").add(target,200,null,"#EXTM3U\n#EXTINF:-1,Test\n../live.m3u8\n");
    var result=HttpsPlaylistFetcher.fetch(start,valid);
    check(result.finalUrl.equals(target),"final URL returned");
    check(M3uPlaylist.parseFromUrl(result.text,result.finalUrl).get(0).uri.equals("https://cdn.example/live.m3u8"),"relative entries use final URL");
    for(Fake f:valid.map.values()){check(f.disconnected,"all connections closed");check(!f.getInstanceFollowRedirects(),"platform redirects disabled");}
    check(!valid.map.get(start).read,"redirect body not read");
    Route five=new Route();int[] statuses={301,302,303,307,308};
    for(int i=0;i<5;i++)five.add("https://a.example/"+i,statuses[i],"/"+(i+1),"");
    five.add("https://a.example/5",200,null,"#EXTM3U\nhttps://media.example/live\n");
    check(HttpsPlaylistFetcher.fetch("https://a.example/0",five).finalUrl.endsWith("/5"),"five redirects and all supported status codes");
    Route six=new Route();for(int i=0;i<=5;i++)six.add("https://a.example/"+i,302,"/"+(i+1),"");
    reject("https://a.example/0",six,"5 次");check(six.opened.size()==6,"sixth redirect target never opened");
    for(String bad:new String[]{"http://bad.example/?secret=token","https://user:secret@bad.example/","file:///secret","//user:secret@bad.example/"}) {
      Route r=new Route().add(start,302,bad,"");reject(start,r,"只允許");check(r.opened.size()==1,"unsafe redirect target never opened");
    }
    Route initial=new Route();reject("http://bad.example/?secret=token",initial,"只允許");check(initial.opened.isEmpty(),"HTTP initial URL never opened");
    reject("https://user:secret@bad.example/",initial,"只允許");
    reject(start,new Route().add(start,302,null,""),"Location");
    reject(start,new Route().add(start,302," ",""),"Location");
    reject(start,new Route().add(start,302,"https://bad.example/[secret", ""),"下載失敗");
    Route loop=new Route().add(start,302,start+"#fragment","");reject(start,loop,"迴圈");check(loop.opened.size()==1,"fragment loop blocked before reopen");
    reject(start,new Route().add(start,403,null,"secret"),"403");
    reject(start,new Route(),"下載失敗");
    Route oversized=new Route().add(start,200,null,"x".repeat(M3uPlaylist.MAX_CHARS+1));reject(start,oversized,"8 MiB");
    check(oversized.map.get(start).disconnected,"oversize body disconnected");
    Route advertised=new Route().add(start,200,null,"");advertised.map.get(start).length=M3uPlaylist.MAX_CHARS+1L;
    reject(start,advertised,"8 MiB");check(!advertised.map.get(start).read,"oversize Content-Length rejected before body");
    System.out.println("HTTPS playlist fetcher: "+checks+" checks passed (fake connections; no network)");
  }
}
