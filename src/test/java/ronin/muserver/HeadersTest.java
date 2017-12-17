package ronin.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static ronin.muserver.MuServerBuilder.muServer;

public class HeadersTest {

	private final OkHttpClient client = new OkHttpClient();

	@Test
	public void canGetAndSetThem() throws InterruptedException, IOException {
		MuServer server = muServer()
				.addHandler((request, response) -> {
					for (Map.Entry<String, String> entry : request.headers()) {
						System.out.println(entry);
					}
					String something = request.headers().get("X-Something");
					response.headers().add("X-Response", something);
					return true;
				}).start();

		String randomValue = UUID.randomUUID().toString();

		Response resp = client.newCall(new Request.Builder()
				.header("X-Something", randomValue)
				.url(server.url())
				.build()).execute();


		server.stop();

		assertThat(resp.header("X-Response"), equalTo(randomValue));

	}
}
