import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main() {
    val model: LocalModel = CliLocalModel(
        modelPath = MODEL_PATH,
        binPath = LITERT_BIN,
        libDir = LITERT_LIB_DIR,
        backend = LITERT_BACKEND
    )

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", SERVER_PORT), 0)
    server.createContext("/health", HealthHandler(model))
    server.createContext("/v1/messages", MessagesHandler(model))
    server.executor = null
    server.start()

    println("Server started on http://127.0.0.1:$SERVER_PORT")
    println("Using model path: $MODEL_PATH")
    println("Model implementation: ${model::class.simpleName}")
}
