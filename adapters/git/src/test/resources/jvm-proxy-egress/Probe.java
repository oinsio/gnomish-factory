import java.net.HttpURLConnection;
import java.net.URI;

/**
 * The in-box HTTP probe of JvmProxyEgressE2ESpec, run with the single-file source launcher: GETs
 * the URL it is given and prints the status line, so the spec reads where the request went.
 */
public class Probe {
    public static void main(String[] args) throws Exception {
        var connection = (HttpURLConnection) URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        System.out.println("HTTP " + connection.getResponseCode());
    }
}
