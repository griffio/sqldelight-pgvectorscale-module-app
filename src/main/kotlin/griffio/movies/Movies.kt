package griffio.movies

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.pgvector.PGbit
import com.pgvector.PGvector
import griffio.migrations.Items
import griffio.queries.Sample
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI

private fun getSqlDriver() = PGSimpleDataSource().apply {
    setURL("jdbc:postgresql://localhost:5432/vector")
    applicationName = "App Main"
    user = "postgres"
    password = "postgres"
}.asJdbcDriver()

val bitVectorAdapter = object: ColumnAdapter<PGbit, String> {
    override fun decode(databaseValue: String): PGbit = PGbit(databaseValue)
    override fun encode(value: PGbit): String = value.toString()
}
val vectorAdapter = object: ColumnAdapter<PGvector, String> {
    override fun decode(databaseValue: String): PGvector = PGvector(databaseValue)
    override fun encode(value: PGvector): String = value.toString()
}

val adapters = Items.Adapter(vectorAdapter, bitVectorAdapter)

fun main() {
    // use Ollama locally with an embedding model
    val http = EmbeddingsClient(URI.create("http://localhost:11434"), "qwen3-embedding:0.6b")
    val driver = getSqlDriver()
    val sample = Sample(driver, adapters)

    val movies = sample.movieQueries.select().executeAsList()

    for (movie in movies) {
        val response = http.embeddings(movie.plot_summary)
        sample.movieQueries.update(movieId = movie.id, embeddingResponse = response)
    }

    fun search(prompt: String) {
        val searchResponse = http.embeddings(prompt)
        val header = "-".repeat(prompt.length)
        println(header)
        println(prompt)
        println(header)
        sample.movieQueries.similaritySearch(searchResponse).executeAsList().forEach { movie ->
            println("""${movie.title} ( ${movie.year} ${movie.genre} ) Score: ${movie.similarityScore}""")
        }
    }

    // pgvectorscale: diskann-backed search with query-time GUC tuning.
    // SET LOCAL only applies within a transaction - outside one, each statement
    // runs on its own connection and the setting would be silently lost
    fun searchTuned(prompt: String) {
        val searchResponse = http.embeddings(prompt)
        val header = "-".repeat(prompt.length)
        println(header)
        println("$prompt (diskann tuned)")
        println(header)
        sample.pgvectorscaleQueries.transactionWithResult {
            sample.pgvectorscaleQueries.setDiskannQuerySearchListSize()
            sample.pgvectorscaleQueries.setDiskannQueryRescore()
            sample.pgvectorscaleQueries.similaritySearchDiskann(searchResponse).executeAsList()
        }.forEach { movie ->
            println("""${movie.title} ( ${movie.year} ${movie.genre} ) Score: ${movie.similarityScore}""")
        }
    }

    // pgvectorscale: label-filtered search (1 = Sci-Fi, 2 = Drama, 3 = Comedy, 4 = Thriller)
    fun searchFiltered(prompt: String, labels: Array<Short>) {
        val searchResponse = http.embeddings(prompt)
        val header = "-".repeat(prompt.length)
        println(header)
        println("$prompt (labels ${labels.contentToString()})")
        println(header)
        sample.pgvectorscaleQueries.similaritySearchDiskannFiltered(searchResponse, labels).executeAsList().forEach { movie ->
            println("""${movie.title} ( ${movie.year} ${movie.genre} labels ${movie.labels?.contentToString()} ) Score: ${movie.similarityScore}""")
        }
    }

    search("Tell me some movies that have a dystopian setting in them.")
    search("Movies about questioning what's real.")
    search("Films where time works differently.")
    search("Something uplifting about never giving up.")
    search("Stories about rich and poor people.")

    searchTuned("Movies about questioning what's real.")
    searchFiltered("Films where time works differently.", arrayOf(1, 2))
    searchFiltered("Stories about rich and poor people.", arrayOf(4))

}
