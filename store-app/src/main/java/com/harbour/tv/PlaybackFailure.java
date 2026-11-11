package com.harbour.tv;
import androidx.media3.common.PlaybackException;
import com.harbour.core.RecoveryPolicy;

/** Fixed local messages only; never expose request URLs, keys or credentials. */
final class PlaybackFailure {
 final RecoveryPolicy.Fault fault;
 final String message;
 private PlaybackFailure(RecoveryPolicy.Fault fault,String message){this.fault=fault;this.message=message;}
 static PlaybackFailure classify(int code,int http,Throwable cause){
  if(http==429)return new PlaybackFailure(RecoveryPolicy.Fault.RATE_LIMITED,
      "來源限制請求頻率（HTTP 429）；已停止自動重試，請稍後再試。");
  if(http==401||http==403||http==451)return new PlaybackFailure(RecoveryPolicy.Fault.ACCESS_DENIED,
      "來源拒絕存取（HTTP "+http+"）；請確認來源使用條件，不會自動繞過限制。");
  if(code>=PlaybackException.ERROR_CODE_DRM_UNSPECIFIED&&code<=PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED)
   return new PlaybackFailure(RecoveryPolicy.Fault.DRM,"內容授權或 DRM 驗證失敗；已停止播放。");
  boolean tls=false,expired=false;
  for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()){
   expired|=cause instanceof java.security.cert.CertificateExpiredException;
   tls|=cause instanceof java.security.cert.CertificateException||cause instanceof javax.net.ssl.SSLHandshakeException||cause instanceof javax.net.ssl.SSLPeerUnverifiedException;
  }
  if(tls)return new PlaybackFailure(RecoveryPolicy.Fault.SECURITY,expired
      ?"來源憑證已過期（依本機時間判定）；請更新來源或核對系統時間。未略過驗證。"
      :"來源 TLS 驗證／握手失敗；請核對來源及系統時間。未略過驗證。");
  if(http==404||http==410)return new PlaybackFailure(RecoveryPolicy.Fault.NOT_FOUND,
      "來源已不存在（HTTP "+http+"）；請更新片單或選擇其他可用來源。");
  if(code==PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED||code==PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
   return new PlaybackFailure(RecoveryPolicy.Fault.UNSUPPORTED,"此來源格式或解碼不支援；請選擇其他可用來源。");
  if(code==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
   return new PlaybackFailure(RecoveryPolicy.Fault.TRANSIENT,"來源連線逾時；已重試仍無回應，請換另一條線路或更新片單。");
  if(code==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
   return new PlaybackFailure(RecoveryPolicy.Fault.TRANSIENT,"無法連線至來源；請檢查電視網路，或換另一條線路。");
  return new PlaybackFailure(RecoveryPolicy.Fault.TRANSIENT,null);
 }
}
