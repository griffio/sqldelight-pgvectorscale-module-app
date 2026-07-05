# SqlDelight 2.3.x Postgresql PgVectorscale module support prototype

https://github.com/cashapp/sqldelight

**Experimental**

Use with SqlDelight `2.3.x` or higher

Supports[pgvectorscale](https://github.com/timescale/pgvectorscale) extension
(StreamingDiskANN `diskann` index, index build parameters, query-time tuning and
label-based filtered search).

---

## Usage

Instead of a new dialect or adding PostgreSql extensions into the core PostgreSql grammar e.g. https://postgis.net/ and https://github.com/pgvector/pgvector

Use a custom SqlDelight module to implement grammar and type resolvers for PgVector operations

```kotlin
sqldelight {
    databases {
        create("Sample") {
            deriveSchemaFromMigrations.set(true)
            migrationOutputDirectory = file("$buildDir/generated/migrations")
            migrationOutputFileFormat = ".sql"
            packageName.set("griffio.queries")
            dialect(libs.sqldelight.postgresql.dialect)
            module(project(":pgvectorscale-module")) // module can be local project
            // or external dependency module("io.github.griffio:sqldelight-pgvectorscale:0.0.1")
        }
    }
}
```

module published in Maven Central https://central.sonatype.com/artifact/io.github.griffio/sqldelight-pgvectorscale/versions

`io.github.griffio:sqldelight-pgvectorscale:0.0.1`

```sql

CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    embedding VECTOR(3),
    bits BIT(3)
);

CREATE INDEX idx_embedding_hnsw ON items USING hnsw (embedding vector_l2_ops);

CREATE INDEX idx_embedding_ivfflat ON items USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);

insert:
INSERT INTO items (embedding, bits) VALUES ('[1,2,3]', '000'), ('[4,5,6], '111');

select:
SELECT *
FROM items;

selectEmbeddings:
SELECT * FROM items ORDER BY embedding <-> '[3,1,2]' LIMIT 5;

selectWithVector:
SELECT * FROM items ORDER BY embedding <-> ?::VECTOR LIMIT 5;

selectSubVector:
SELECT subvector(?::VECTOR, 1, 3);

selectCosineDistance:
SELECT cosine_distance('[1,1]'::VECTOR, '[-1,-1]');

selectBinaryQuantize:
SELECT binary_quantize('[0,0.1,-0.2,-0.3,0.4,0.5,0.6,-0.7,0.8,-0.9,1]'::VECTOR);
```

---

## PgVectorscale

[pgvectorscale](https://github.com/timescale/pgvectorscale) builds on pgvector with the
StreamingDiskANN index (`USING diskann`) and Statistical Binary Quantization. It adds no
new SQL types or operators - the module only needed the `diskann` index method and its
index build parameter names (plus pgvector's own `m` / `ef_construction` hnsw parameters).

```sql
-- CASCADE installs pgvector automatically
CREATE EXTENSION IF NOT EXISTS vectorscale CASCADE;

CREATE INDEX idx_movie_embedding_diskann ON movie_plots
USING diskann (embedding vector_cosine_ops)
WITH (storage_layout = memory_optimized, num_neighbors = 50, search_list_size = 100, max_alpha = 1.2);
```

Label-based filtered search uses a `SMALLINT[]` column indexed alongside the embedding
and filtered with the `&&` (overlap) operator:

```sql
ALTER TABLE movie_plots ADD COLUMN labels SMALLINT[];

CREATE INDEX idx_movie_embedding_diskann_labels ON movie_plots
USING diskann (embedding vector_cosine_ops, labels);

similaritySearchDiskannFiltered:
WITH search(vec) AS (
  SELECT jsonb_path_query_first(:embeddingResponse::JSONB, '$.data[*].embedding')::TEXT::VECTOR
)
SELECT id, title, year, genre, labels, 1 - (embedding <=> search.vec) AS similarityScore
FROM movie_plots, search
WHERE labels && :labels::SMALLINT[]
ORDER BY embedding <=> search.vec
LIMIT 5;
```

The `:labels::SMALLINT[]` cast makes SqlDelight generate an `Array<Short>` bind argument
and keeps both sides of `&&` as `smallint[]` so the label index stays usable.

Query-time tuning uses the diskann GUCs. `SET` values must be literals (not bind
parameters), and `SET LOCAL` must run inside a transaction - the SqlDelight JDBC driver
uses a fresh connection per statement outside a transaction, so a session-level `SET`
would be silently lost:

```sql
setDiskannQuerySearchListSize:
SET LOCAL diskann.query_search_list_size = 200;

setDiskannQueryRescore:
SET LOCAL diskann.query_rescore = 100;
```

```kotlin
sample.pgvectorscaleQueries.transactionWithResult {
    sample.pgvectorscaleQueries.setDiskannQuerySearchListSize()
    sample.pgvectorscaleQueries.setDiskannQueryRescore()
    sample.pgvectorscaleQueries.similaritySearchDiskann(searchResponse).executeAsList()
}
```

Notes

* SqlDelight `2.3.2` does not parse `ARRAY[1,2]` constructor syntax (added to the
  PostgreSql dialect after 2.3.2) - use text array literals with a cast instead:
  `'{1,2}'::SMALLINT[]`
* Migrations `V1`/`V2` run on plain pgvector; `V3` onwards require the `vectorscale`
  extension (use the docker-compose setup below, or install pgvectorscale locally).
  Running `flywayMigrate` against a plain pgvector server fails at `V3`.

---

Movies Sample

Create LLM embeddings from the movie plot summaries and use prompt.

Uses cosine similarity to find movie plot summaries that are closest to a given prompt.
`V3` replaces the hnsw cosine index with a diskann index so the similarity searches are
served by pgvectorscale; `V4` adds genre labels (`1` Sci-Fi, `2` Drama, `3` Comedy, `4`
Thriller) for filtered search.

```sql
-- Create the movie_plots table with an embedding column
CREATE TABLE movie_plots (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    year INTEGER,
    genre VARCHAR(100),
    plot_summary TEXT NOT NULL,
    embedding VECTOR(1024)  -- Adjust dimension based on your embedding model
);
```

```sql

select:
SELECT id, title, year, genre, plot_summary FROM movie_plots ORDER BY year;

update:
UPDATE movie_plots
SET embedding = jsonb_path_query_first(:embeddingResponse::JSONB, '$.data[*].embedding')::TEXT::VECTOR
WHERE id = :movieId;

similaritySearch:
WITH search(vec) AS (
  SELECT jsonb_path_query_first(:embeddingResponse::JSONB, '$.data[*].embedding')::TEXT::VECTOR
)
SELECT id, title, year, genre, 1 - (embedding <=> search.vec) AS similarityScore
FROM movie_plots, search
ORDER BY embedding <=> search.vec;


```

```
-------------------------------------
Movies about questioning what's real.
-------------------------------------
The Truman Show ( 1998 Sci-Fi Drama Comedy ) Score: 0.4663206020784745
The Matrix ( 1999 Sci-Fi Action ) Score: 0.46191100435653554
Eternal Sunshine of the Spotless Mind ( 2004 Romantic Sci-Fi Drama ) Score: 0.4469298720359802
Inception ( 2010 Sci-Fi Thriller ) Score: 0.40091077083881144
Interstellar ( 2014 Sci-Fi Drama ) Score: 0.35195842385292053
Up ( 2009 Animation Adventure ) Score: 0.3490072412799696
Arrival ( 2016 Sci-Fi Drama ) Score: 0.33152367692903795
Parasite ( 2019 Dark Comedy Thriller ) Score: 0.29843956232070923
The Shawshank Redemption ( 1994 Drama ) Score: 0.25976793526219977
Groundhog Day ( 1993 Comedy Fantasy ) Score: 0.25180159498443677
```

see https://github.com/pgvector/pgvector/blob/master/test/sql/vector_type.sql

**TODO**

Add more types - halfvec, sparse vectors 

Extending an existing grammar through more than one level of inheritance isn't supported by grammar generator -  
This would require fixes to https://github.com/sqldelight/Grammar-Kit-Composer - work around is to override manually and
chain the previous rule that another SqlDelight module maybe overriding (e.g. PgVector)

e.g.

```kotlin

val previousTypeName = PostgreSqlParserUtil.type_name
val previousExtensionExpr = PostgreSqlParserUtil.extension_expr
val previousIndexMethod = PostgreSqlParserUtil.index_method
val previousStorageParameters = PostgreSqlParserUtil.storage_parameters

override fun setup() {
    PgvectorParserUtil.reset()
    PgvectorParserUtil.overridePostgreSqlParser()
    // As the grammar doesn't support inheritance - override type_name manually to try inherited type_name
    PostgreSqlParserUtil.type_name = Parser { psiBuilder, i ->
        type_name?.parse(psiBuilder, i)
                ?: PgvectorParser.type_name_real(psiBuilder, i)
                || previousTypeName?.parse(psiBuilder, i)
                ?: PostgreSqlParser.type_name_real(psiBuilder, i)
    }
    // doesn't support inheritance - override extension_expr manually to try inherited extension_expr
    PostgreSqlParserUtil.extension_expr = Parser { psiBuilder, i ->
        extension_expr?.parse(psiBuilder, i)
                ?: PgvectorParser.extension_expr_real(psiBuilder, i)
                || previousExtensionExpr?.parse(psiBuilder, i)
                ?: PostgreSqlParser.extension_expr_real(psiBuilder, i)
    }
    // etc
    PostgreSqlParserUtil.index_method = Parser { psiBuilder, i ->
        index_method?.parse(psiBuilder, i)
                ?: PgvectorParser.index_method_real(psiBuilder, i)
                || previousIndexMethod?.parse(psiBuilder, i)
                ?: PostgreSqlParser.index_method_real(psiBuilder, i)
    }
    // etc
    PostgreSqlParserUtil.storage_parameters = Parser { psiBuilder, i ->
        storage_parameters?.parse(psiBuilder, i)
                ?: PgvectorParser.storage_parameters_real(psiBuilder, i)
                || previousStorageParameters?.parse(psiBuilder, i)
                ?: PostgreSqlParser.storage_parameters_real(psiBuilder, i)      
    }
}
```

SqlDelight needs this fix https://github.com/sqldelight/sqldelight/pull/5677 for the modules to work as the
PostgreSqlTypeResolver needs to be (open) inheritable rather than use delegation e.g. override `definitionType` method
expects to be called via inheritance.

Use Jdbc types https://github.com/pgvector/pgvector-java either directly in type resolver or using SqlDelight type adapters

## Running

Start PostgreSQL with pgvectorscale (timescale/timescaledb-ha image), then
build and migrate:

```shell
docker compose up -d &&
./gradlew build &&
./gradlew flywayMigrate
```

Run the pgvector items demo (`griffio.MainKt`):

```shell
./gradlew run
```

The movies demo (`griffio.movies.MoviesKt`) needs a local [Ollama](https://ollama.com)
with an embedding model (`ollama pull qwen3-embedding:0.6b`) - run it from the IDE, or:

