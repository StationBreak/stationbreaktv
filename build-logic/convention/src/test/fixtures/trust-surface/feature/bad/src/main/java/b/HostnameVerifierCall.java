package b;

class HostnameVerifierCall {
    void f(okhttp3.OkHttpClient.Builder b, javax.net.ssl.SSLSocketFactory s, javax.net.ssl.X509TrustManager m) {
        b.hostnameVerifier(null);
        b.sslSocketFactory(s, m);
    }
}
