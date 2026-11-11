package com.harbour.tv;

import android.content.Context;
import android.net.Uri;
import android.security.NetworkSecurityPolicy;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import com.harbour.core.Catalog;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;

/** Bound to one source. Every manifest, segment and key open passes the same request boundary. */
@UnstableApi
final class RestrictedPlaybackDataSource extends BaseDataSource {
  private final Context context;
  private final Catalog.Source source;
  private final String territory;
  private final HttpPlaybackConsent consent;
  /** One playback's redirect aliases. Refresh the stable entry; never manufacture signed URLs. */
  static final class ManifestRedirects {
    private final String root;
    private final java.util.Set<String> resolved=new java.util.LinkedHashSet<>();
    ManifestRedirects(String root){this.root=root;}
    synchronized Uri refreshUri(Uri requested){return resolved.contains(requested.toString())?Uri.parse(root):requested;}
    synchronized void record(Uri requested,Uri actual){
      if(requested!=null&&actual!=null&&root.equals(requested.toString())&&!root.equals(actual.toString())) {
        if(resolved.size()>=32){java.util.Iterator<String> aliases=resolved.iterator();aliases.next();aliases.next();aliases.remove();}
        resolved.add(actual.toString());
      }
    }
  }
  private final ManifestRedirects manifestRedirects;
  private DataSource delegate;
  private HttpURLConnection connection;
  private InputStream input;
  private URI httpUri;
  private Uri openedUri;
  private long remaining = C.LENGTH_UNSET;
  private boolean opened;

  RestrictedPlaybackDataSource(Context context, Catalog.Source source, String territory) {
    this(context,source,territory,new ManifestRedirects(source.uri));
  }

  RestrictedPlaybackDataSource(Context context,Catalog.Source source,String territory,ManifestRedirects redirects) {
    super(true);
    this.manifestRedirects=redirects;
    this.context = context.getApplicationContext();
    this.source = source;
    this.territory = territory;
    consent = new HttpPlaybackConsent(context);
  }

  private void requireHttpPermission(URI request) throws IOException {
    URI root;
    try { root = URI.create(source.uri); }
    catch (RuntimeException invalid) { throw new IOException("Invalid source URI"); }
    if (root.getHost() == null || request.getHost() == null
        || !consent.permitsRequest(source.id,root,request))
      throw new IOException("HTTP source/host consent required");
    if (!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(request.getHost()))
      throw new IOException("HTTP host rejected by Android network security policy");
  }

  static URI redirectTarget(URI current,String location,int count) throws IOException {
    if(count>=5||location==null||location.trim().isEmpty())throw new IOException("Invalid or excessive redirect");
    try {URI target=current.resolve(location.trim());
      if(target.getHost()==null||target.getUserInfo()!=null||target.getFragment()!=null
        ||!("http".equalsIgnoreCase(target.getScheme())||"https".equalsIgnoreCase(target.getScheme())))throw new IOException("Invalid redirect target");
      return target;
    } catch(IllegalArgumentException e){throw new IOException("Invalid redirect URI",e);}
  }

  static void checkHttpHeaders(Map<String, String> headers) throws IOException {
    for (String name : headers.keySet()) {
      if (name == null || Arrays.asList("authorization", "proxy-authorization", "cookie", "host")
          .contains(name.trim().toLowerCase(Locale.ROOT)))
        throw new IOException("Sensitive headers are not allowed on reviewed HTTP playback");
    }
  }

  @Override public long open(DataSpec spec) throws IOException {
    spec=spec.withUri(manifestRedirects.refreshUri(spec.uri));
    transferInitializing(spec);
    try {
      String blocked = source.blockedReason(territory, Instant.now());
      if (blocked != null) throw new IOException(blocked);
      URI uri;
      try { uri = URI.create(spec.uri.toString()); }
      catch (RuntimeException invalid) { throw new IOException("Invalid media request URI"); }
      String scheme = uri.getScheme();
      if ("https".equalsIgnoreCase(scheme)) {
        if (uri.getHost() == null || uri.getUserInfo() != null)
          throw new IOException("Invalid HTTPS media host");
        delegate = new DefaultHttpDataSource.Factory()
            .setUserAgent("ShunpinTV/" + BuildConfig.VERSION_NAME)
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(false).createDataSource();
        remaining = delegate.open(spec);
      } else if ("http".equalsIgnoreCase(scheme)) {
        requireHttpPermission(uri);
        checkHttpHeaders(spec.httpRequestHeaders);
        if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || spec.httpBody != null)
          throw new IOException("Only GET is supported for reviewed HTTP media");
        URI current=uri;
        for(int redirects=0;;redirects++) {
        if("https".equalsIgnoreCase(current.getScheme())) {
          httpUri=URI.create(source.uri); // Revoking root HTTP consent also stops an upgraded stream.
          delegate=new DefaultHttpDataSource.Factory().setUserAgent("ShunpinTV/"+BuildConfig.VERSION_NAME)
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(false).createDataSource();
          remaining=delegate.open(spec.withUri(Uri.parse(current.toString())));break;
        }
        requireHttpPermission(current);
        httpUri = current;
        connection = (HttpURLConnection) current.toURL().openConnection();
        // Standard platform networking still enforces the OS cleartext policy. No sockets/proxy.
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "ShunpinTV/" + BuildConfig.VERSION_NAME);
        for (Map.Entry<String, String> header : spec.httpRequestHeaders.entrySet())
          connection.setRequestProperty(header.getKey(), header.getValue());
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (spec.position != 0 || spec.length != C.LENGTH_UNSET) {
          String range = "bytes=" + spec.position + "-";
          if (spec.length != C.LENGTH_UNSET) range += spec.position + spec.length - 1;
          connection.setRequestProperty("Range", range);
        }
        int code = connection.getResponseCode();
        if(BuildConfig.DEBUG && (code>=300 || current.getPath().endsWith(".m3u8"))) android.util.Log.i("ShunpinHttp","status="+code+" host="+current.getHost()+" path="+current.getPath());
        if(code==301||code==302||code==303||code==307||code==308) {
          String location=connection.getHeaderField("Location");
          URI target=redirectTarget(current,location,redirects);
          connection.disconnect();connection=null;current=target;continue;
        }
        if (code < 200 || code > 299)
          throw new HttpDataSource.InvalidResponseCodeException(
              code, null, null, Collections.emptyMap(), spec, new byte[0]);
        if (spec.position > 0 && code != 206)
          throw new IOException("HTTP server ignored required byte range");
        input = connection.getInputStream();
        remaining = spec.length != C.LENGTH_UNSET ? spec.length : connection.getContentLengthLong();
        break;
        }
      } else if (source.rights == Catalog.Rights.OWNED_DEMO && "asset".equalsIgnoreCase(scheme)
          && (spec.uri.toString().equals(source.uri)
              || (source.uri.equals("asset:///hls-owned/playlist.m3u8")
                  && spec.uri.toString().matches("asset:///hls-owned/[a-zA-Z0-9_-]+\\.(ts|m4s|mp4|m3u8)")))) {
        delegate = new DefaultDataSource.Factory(context).createDataSource();
        remaining = delegate.open(spec);
      } else {
        throw new IOException("Unsupported media request scheme");
      }
      openedUri = httpUri != null && delegate == null ? Uri.parse(httpUri.toString()) : spec.uri;
      manifestRedirects.record(spec.uri, getUri());
      opened = true;
      transferStarted(spec);
      return remaining;
    } catch (IOException | RuntimeException failure) {
      try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  @Override public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (httpUri != null) requireHttpPermission(httpUri); // Revocation also stops an open HTTP stream.
    if (remaining == 0) return C.RESULT_END_OF_INPUT;
    int limit = remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining);
    int count = delegate != null ? delegate.read(buffer, offset, limit) : input.read(buffer, offset, limit);
    if (count > 0) {
      if (remaining != C.LENGTH_UNSET) remaining -= count;
      bytesTransferred(count);
    }
    return count;
  }

  @Override public Uri getUri() { return delegate != null ? delegate.getUri() : openedUri; }
  @Override public Map<String, List<String>> getResponseHeaders() {
    if (delegate != null) return delegate.getResponseHeaders();
    return connection == null ? Collections.emptyMap() : connection.getHeaderFields();
  }

  @Override public void close() throws IOException {
    try {
      if (delegate != null) delegate.close();
      else if (input != null) input.close();
    } finally {
      if (connection != null) connection.disconnect();
      delegate = null; input = null; connection = null; httpUri = null; openedUri = null;
      remaining = C.LENGTH_UNSET;
      if (opened) { opened = false; transferEnded(); }
    }
  }
}
