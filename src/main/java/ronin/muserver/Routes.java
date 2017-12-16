package ronin.muserver;

public class Routes {
	public static MuHandler route(HttpMethod method, String path, MuHandler muHandler) {
		return (request, response) -> {
			boolean methodMatches = method == null || method.equals(request.method());
			if (methodMatches && request.uri().getPath().matches(path)) {
				return muHandler.handle(request, response);
			}
			return false;
		};
	}

	private Routes() {}
}
