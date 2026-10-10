# Spark Connect in kotllm - a design sketch

**Status: proposed, nothing built.** Written because the shape is unusually good here and worth
deciding on deliberately rather than drifting into.

**Note, added for the Kotlin port: Spark Connect is deliberately absent here.**
The Scala build carries `spark-connect-client-jvm_${scala-compat-version}` 4.2.0 as a `provided`
dependency; the Kotlin build carries no Spark dependency of any kind.
That is a decision, not an oversight: the project owner chose to leave it out of the port.
Nothing in the code refers to it, so re-adding it stays what Option A below describes, a one-line
pom change with no code to write.
The rest of this document is kept as the design record for that option.

---

## Why this is different from every other dependency we have said no to

We have now refused DuckDB (73MB JDBC jar), GraalPy, GraalJS and Jep, each on the same grounds:
the fat jar *is* the standard library handed to the LLM, so a large dependency inflates the
injected manifest and the download for a single slice of functionality.

Spark looks like the worst offender - `spark-sql` plus transitives is 300–400MB. **Spark Connect
changes the calculation completely**, and in a way that lines up with every architectural decision
made in the last three increments.

Since 3.4, a Spark client is a **thin gRPC client** that builds an unresolved logical plan and
ships it to a server. `spark-connect-client-jvm` is tens of MB, not hundreds. The engine - the
catalyst optimizer, the executors, the 400MB - lives in a process we do not ship and did not
start.

And crucially, **the client is native Scala**. Not a binding, not a JNI shim, not a subprocess
protocol we invented: `SparkSession.builder().remote("sc://host:15002").getOrCreate()` returns a
`Dataset[Row]` API in the exact language `scala_eval` already interprets, via the embedded
`scala.tools.nsc.interpreter.IMain` - unaffected by kotllm's own implementation language, Kotlin
or Scala.
Of everything considered, this is the only capability that arrives as *ordinary Scala library
code*.

---

## The three designs, in order of how much they ask for

### A. Nothing new - Spark Connect from `scala_eval` today

Add `spark-connect-client-jvm` to the pom. It lands on `Classpath.runtimeEntries`, which is what
`ScalaEngine` uses, so the model can already write:

```scala
val spark = org.apache.spark.sql.SparkSession.builder().remote("sc://localhost:15002").getOrCreate()
spark.read.parquet("/data/events").groupBy($"service").count().orderBy($"count".desc).show(5)
```

No new tool, no new engine, no code at all beyond a pom entry and a line in `Manifest`.

**This is almost certainly where to start.** It is the cheapest possible test of whether the
capability is wanted, and it composes with things already built: `read_to_var` and
`write_from_eval` work unchanged, because a `SparkSession` is just another value in the session.
`session_vars` would list it.

Costs, honestly:

- **Tens of MB on the fat jar** and a longer injected dependency manifest, paid by every user
  whether or not they have a Spark cluster.
- **The model must know the remote URL**, which means config and a prompt hint, or it will guess
  `local[*]` and get a `ClassNotFoundException` for the full engine we did not bundle. That error
  is genuinely confusing and worth pre-empting.
- Spark Connect pins its own gRPC, Netty and protobuf versions. **This is the real risk** and it
  is exactly the hazard CLAUDE.md already documents for the jline uber-jar: a shaded fat jar with
  two protobuf versions in it fails at runtime, far from the change that caused it. Anyone trying
  this must run `mvn dependency:tree` and check for protobuf/netty duplication *before* believing
  it works.

### B. A `spark` tool - SQL against a configured cluster

One tool, mirroring `python_eval`'s shape:

```
spark_sql{query} -> a rendered table, or a row count plus a written file
```

Backed by a lazily-created `SparkSession` from `spark_remote` in config. Registered **only when
that key is set**, the established rule (`search` with no index, `java` with no `jdk.jshell`,
`python` with no launcher).

Why a separate tool rather than leaving it to `scala_eval`: the same argument that produced the
file tools. `spark.read.parquet(...).groupBy(...)` through `scala_eval` means the model writes
Scala *about* Spark, gets a `Dataset` back, and has to remember to `.show()` or `.collect()` -
and `.collect()` on a large result is a foot-gun that pulls a cluster's worth of rows into the
driver and then into the transcript. A dedicated tool can:

- cap rows returned and **say how many were withheld**, like `search` and `grep` do;
- route a large result straight to a file via `write_from_eval`'s discipline, never through the
  conversation;
- accept SQL, which the model is markedly more fluent in than in the Dataset DSL.

### C. A fourth `Interpreter` - `spark` as a `language`

Tempting because the SPI exists and it would be four methods. **I think this is wrong**, and
worth writing down why.

`Interpreter` means "a session I can hold arbitrary values in and evaluate arbitrary code
against". Spark Connect is not that: it is a query engine with a schema, where the unit is a
plan and not a statement, and `bindFile` has no sensible meaning (you would want
`spark.read.csv(path)`, producing a DataFrame, not a String). Forcing it through the SPI would
mean four methods of which two lie.

The honest fit is **B**, with the session reachable from `scala_eval` as in **A** for anything
the tool does not cover.

---

## What it buys, stated carefully

Not speed. At the scale this project has actually been tested on - an 803KB log - Spark is
orders of magnitude *slower* than `python_eval` with pandas, because the whole cost is planning
and scheduling. The ladder, from the earlier conversation:

| Data size | Right tool |
| --- | --- |
| ~1MB | any in-process interpreter. Where every live test so far has been. |
| ~100MB–10GB, one machine | DuckDB, or pandas if it fits in memory |
| beyond one machine | Spark |

So the value is **not** performance on the workloads exercised so far. It is:

1. **Reach.** A model that can query the user's actual lakehouse tables answers questions no
   amount of local cleverness can. This is the same argument as RAG: the corpus matters more than
   the retriever.
2. **Schema as context.** `spark.catalog.listTables()` and a `DESCRIBE` are a few hundred tokens
   that make the model dramatically more useful, and they are cheap. That is arguably a bigger win
   than the query execution.
3. **It is already the user's world.** Stated expertise in both Spark and Spark Connect, and the
   endpoint this project targets is a Databricks workspace - where a Connect endpoint is a
   configuration away, not an installation.

That third point is the actual reason to do it, and it is worth being clear that it is a
*fit-for-this-user* argument rather than a general one.

---

## Sequencing

1. **Try A by hand first.** Add the dependency in a scratch branch, run `mvn dependency:tree`,
   check for protobuf/netty duplication, and see whether the model can drive a real cluster from
   `scala_eval` with nothing but a URL in the prompt. One afternoon, and it answers the only
   question that matters: is this useful enough to pay tens of MB for?
2. **If yes, build B.** `spark_sql` with row caps, schema introspection (`spark_tables`), and
   registration gated on `spark_remote`. Roughly the size of `python_eval`: ~400 lines plus
   tests, and the tests need a Connect server, so they are `-Plive`-tagged like
   `DatabricksLiveTest`.
3. **Do not build C.**

## What would make me abandon it

- `mvn dependency:tree` showing protobuf or netty duplication that cannot be resolved by pinning
  one version. That is a shading hazard in a jar we hand to an interpreter, and the failure mode
  is a `NoSuchMethodError` at runtime that looks like anything but a dependency conflict.
- The client jar turning out to be >100MB once transitives are counted. The number that matters
  is the *shaded* delta, not the coordinate's own size - measure it, the way the DuckDB jar was
  measured at 73MB rather than guessed.
- Needing a `spark` session to answer questions about local files. If the interesting workloads
  are all one-machine, this is a worse `python_eval`.

## The pleasing symmetry, and the thing to resist

Spark Connect and the Python engine reach the same architecture from opposite directions: a thin
client in the host language, a heavyweight engine in another process, a framed protocol between
them, and only plans and answers crossing the boundary. `PythonEngine` hand-rolls that in ~570
lines with newline-delimited JSON (566 in the Kotlin port, up from 474 in Scala); Spark Connect
has gRPC and protobuf doing it properly.

The temptation that follows is to unify them behind one abstraction. **Resist it.** One speaks
plans over gRPC to a cluster; the other speaks statements over a pipe to a local process. They
rhyme; they are not the same thing, and an abstraction over two things that merely rhyme is how
`Interpreter` would acquire two methods that lie.
