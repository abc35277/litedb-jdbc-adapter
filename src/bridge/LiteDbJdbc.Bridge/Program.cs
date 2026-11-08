using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using LiteDB;
using LiteDbJsonSerializer = LiteDB.JsonSerializer;

try
{
    Console.InputEncoding = Encoding.UTF8;
    Console.OutputEncoding = Encoding.UTF8;
    // Buffered stdout: redirected Console.Out flushes every 256 characters by
    // default, which turns each large chunk into hundreds of pipe writes. A 64KB
    // buffer with explicit per-response Flush keeps the line protocol intact while
    // cutting the syscall count per chunk from hundreds to one.
    Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false), 65536)
    {
        AutoFlush = false,
    });
}
catch (Exception)
{
    // Console encodings cannot be set in hosts without a console handle; stdin/stdout
    // stay byte-transparent and the JSON payload escapes non-ASCII anyway.
}

if (args.Length > 0 && args[0].Equals("--version", StringComparison.OrdinalIgnoreCase))
{
    Console.WriteLine(BridgeRuntime.CreateVersionText());
    Console.Out.Flush();
    return;
}

if (args.Length == 0 || args[0].Equals("--stdio", StringComparison.OrdinalIgnoreCase))
{
    await new BridgeServer().RunAsync();
    return;
}

Console.Error.WriteLine("Usage: LiteDbJdbc.Bridge --stdio");
Environment.ExitCode = 2;

internal sealed class BridgeServer : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = false,
    };

    private LiteDatabase? _database;
    private string? _databasePath;
    private bool _readOnly;
    private ConnectionType _connectionType = ConnectionType.Shared;
    // Column-metadata memo (P6): keyed by (collection filter, sample size), scoped to
    // this bridge process. Cleared on Close() and on every schema-affecting request.
    // A second process may write the same file directly, so memo hits additionally
    // verify the database file's size and last-write timestamp as a fallback guard.
    private readonly Dictionary<(string, int), object> columnsMemo = new();
    private DateTime memoFileStampUtc = DateTime.MinValue;
    private long memoFileLength = -1;

    private void InvalidateSchemaMemo()
    {
        columnsMemo.Clear();
    }

    private bool MemoFileUnchanged()
    {
        if (string.IsNullOrEmpty(_databasePath))
        {
            return false;
        }
        var info = new FileInfo(_databasePath);
        return info.Exists
            && info.Length == memoFileLength
            && info.LastWriteTimeUtc == memoFileStampUtc;
    }

    /// <summary>
    /// Reader acquisition as a method group: keeps the streaming enumerator decoupled
    /// from the database handle without restating the call expression.
    /// </summary>
    private static Func<string, BsonValue[], IBsonDataReader> ReaderOn(LiteDatabase database)
    {
        return database.Execute;
    }

    public async Task RunAsync()
    {
        try
        {
            string? line;
            while ((line = await Console.In.ReadLineAsync()) is not null)
            {
                if (string.IsNullOrWhiteSpace(line))
                {
                    continue;
                }

                RpcRequest? request = null;
                try
                {
                    request = System.Text.Json.JsonSerializer.Deserialize<RpcRequest>(line, JsonOptions)
                        ?? throw new InvalidOperationException("Invalid JSON-RPC request.");

                    var result = Handle(request);
                    if (result is IEnumerable<ExecuteChunk> chunks)
                    {
                        foreach (var chunk in chunks)
                        {
                            WriteChunkResponse(request.Id, chunk);
                        }
                    }
                    else
                    {
                        WriteResponse(new RpcResponse(request.Id, true, result, null));
                    }
                }
                catch (Exception ex)
                {
                    WriteResponse(new RpcResponse(request?.Id ?? 0, false, null, new RpcError(ex.GetType().Name, ex.Message)));
                }
            }
        }
        finally
        {
            Dispose();
            try
            {
                Console.Out.Flush();
            }
            catch (Exception)
            {
                // stdout is gone (host closed the pipe); nothing left to report.
            }
        }
    }

    public void Dispose()
    {
        _database?.Dispose();
        _database = null;
    }

    private object? Handle(RpcRequest request)
    {
        var method = request.Method?.Trim().ToLowerInvariant()
            ?? throw new InvalidOperationException("Missing method.");

        // Any write-shaped request invalidates the column metadata memo. "execute" is
        // bumped unconditionally (LiteDB SQL has forms like SELECT INTO that write
        // while starting with SELECT); the memo's payoff is metadata-only browsing.
        if (method is "execute" or "insertdocument" or "updatedocument" or "deletedocuments" or "rollback")
        {
            InvalidateSchemaMemo();
        }

        return method switch
        {
            "open" => Open(request.Params),
            "close" => Close(),
            "isreadonly" => IsReadOnly(),
            "ping" => new Dictionary<string, object?>
            {
                ["ok"] = true,
                ["databasePath"] = _databasePath,
                ["readOnly"] = _readOnly,
                ["connectionMode"] = _connectionType.ToString(),
            },
            "info" => BridgeRuntime.GetInfo(_databasePath, _readOnly, _connectionType),
            "execute" => ExecuteChunked(request.Params),
            "insertdocument" => InsertDocument(request.Params),
            "updatedocument" => UpdateDocument(request.Params),
            "deletedocuments" => DeleteDocuments(request.Params),
            "collections" => Collections(),
            "columns" => Columns(request.Params),
            "indexes" => Indexes(request.Params),
            "begin" => Begin(),
            "commit" => Commit(),
            "rollback" => Rollback(),
            _ => throw new NotSupportedException($"Unsupported bridge method '{request.Method}'."),
        };
    }

    private object Open(JsonElement parameters)
    {
        Close();

        var databasePath = Path.GetFullPath(RequiredString(parameters, "databasePath"));
        var create = OptionalBool(parameters, "create") ?? false;
        if (!create && !File.Exists(databasePath))
        {
            throw new InvalidOperationException(
                $"LiteDB database file was not found: {databasePath}. "
                + "Pass create=true (URL or driver property) to allow creating a new database.");
        }

        var directory = Path.GetDirectoryName(databasePath);
        if (!string.IsNullOrWhiteSpace(directory) && !Directory.Exists(directory))
        {
            throw new DirectoryNotFoundException(
                $"LiteDB database directory was not found: {directory}. If the path contains garbled characters, reselect the file in DBeaver or use a UTF-8 URL/path.");
        }

        var password = OptionalString(parameters, "password");
        _readOnly = OptionalBool(parameters, "readOnly") ?? false;
        _connectionType = ParseConnectionType(OptionalString(parameters, "connectionMode")
            ?? OptionalString(parameters, "connection"));

        var connectionString = new ConnectionString
        {
            Filename = databasePath,
            Password = string.IsNullOrWhiteSpace(password) ? null : password,
            ReadOnly = _readOnly,
            Connection = _connectionType,
        };

        try
        {
            _database = new LiteDatabase(connectionString, BsonMapper.Global);
            _ = _database.GetCollectionNames().ToArray();
            _databasePath = databasePath;
            return BridgeRuntime.GetInfo(_databasePath, _readOnly, _connectionType);
        }
        catch (Exception ex)
        {
            Close();
            throw new InvalidOperationException(CreateOpenError(databasePath, password, ex), ex);
        }
    }

    private object Close()
    {
        _database?.Dispose();
        _database = null;
        _databasePath = null;
        _readOnly = false;
        _connectionType = ConnectionType.Shared;
        columnsMemo.Clear();
        return new Dictionary<string, object?> { ["closed"] = true };
    }

    private object IsReadOnly()
    {
        return new Dictionary<string, object?> { ["readOnly"] = _readOnly };
    }

    /// <summary>
    /// Streams execute results row-at-a-time as bounded chunks: rows are read from the
    /// LiteDB data reader and flushed every 512 rows, so neither the bridge nor a
    /// single JSON response line ever carries a whole result set. Column discovery is
    /// incremental (append-only); rows sent before a late column appeared are narrower
    /// than the final master column list and the driver pads missing cells by index.
    /// </summary>
    /// <summary>
    /// Matches the exact shape "select count(*) from &lt;collection&gt;" (optionally
    /// quoted, trailing semicolon allowed, no WHERE): LiteDB's SQL count scans every
    /// document while the Count() API counts index entries, which is orders of
    /// magnitude faster on large collections - DBeaver's statistics feature sends
    /// exactly this shape.
    /// </summary>
    private static readonly Regex CountStarPattern = new(
        @"^\s*select\s+count\s*\(\s*\*\s*\)\s+from\s+((\[[^\]]+\])|(""[^""]+"")|(`[^`]+`)|([A-Za-z_][A-Za-z0-9_$]*))\s*;?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private object? TryFastCount(string sql)
    {
        var match = CountStarPattern.Match(sql);
        if (!match.Success)
        {
            return null;
        }

        var raw = match.Groups[1].Value.Trim('[', ']', '"', '`');
        var db = RequireDatabase();

        // A count of a collection that does not exist is 0 - do not let GetCollection
        // auto-create it (important for read-only opens).
        var exists = false;
        foreach (var collection in GetCatalog())
        {
            if (collection.Name.Equals(raw, StringComparison.OrdinalIgnoreCase))
            {
                exists = true;
                break;
            }
        }

        var count = exists ? db.GetCollection(raw).Count() : 0L;
        return new Dictionary<string, object?>
        {
            ["columns"] = new List<ColumnInfo> { new("count", "BIGINT", true, 1) },
            ["rows"] = new List<object?[]> { new object?[] { count } },
            ["rowCount"] = 1,
            ["sourceCollections"] = new[] { raw },
        };
    }

    private IEnumerable<ExecuteChunk> ExecuteChunked(JsonElement parameters)
    {
        const int DefaultChunkSize = 512;
        var statement = RequiredString(parameters, "sql");
        var maxRows = OptionalInt(parameters, "maxRows") ?? 0;
        var args = ReadParameters(parameters);
        // Tools set setFetchSize to their page size; honor it as the flush granularity
        // so stream batches match how the caller consumes rows.
        var chunkSize = Math.Clamp(OptionalInt(parameters, "chunkSize") ?? DefaultChunkSize, 1, 10000);

        var fastCount = TryFastCount(statement);
        if (fastCount is not null)
        {
            // Intercepted "select count(*) from <collection>": serve from the index
            // Count() API instead of the full document scan that SQL count performs.
            var countColumns = (List<ColumnInfo>)((Dictionary<string, object?>)fastCount)["columns"];
            var countRows = (List<object?[]>)((Dictionary<string, object?>)fastCount)["rows"];
            var countCollections = (string[])((Dictionary<string, object?>)fastCount)["sourceCollections"];
            yield return new ExecuteChunk(true, countColumns.ToArray(),
                countRows.Select(r => r).ToArray(), 1, countCollections);
            yield break;
        }

        using var reader = ReaderOn(RequireDatabase())(statement, args);
        var columns = new List<ColumnInfo>();
        var columnLookup = new Dictionary<string, int>();
        var sourceCollections = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var batchRows = new List<object?[]>();
        var totalRows = 0;

        while (reader.Read())
        {
            if (!string.IsNullOrWhiteSpace(reader.Collection))
            {
                sourceCollections.Add(reader.Collection);
            }

            // Single-pass row build: discover/merge columns and fill the row buffer in
            // one traversal (the intermediate per-row dictionary was pure overhead).
            // Semantics preserved: columns are append-only in first-seen order, lookups
            // are case-sensitive, MergeType runs for every field. NOTE: a failed
            // TryGetValue leaves `index` at default(0) - new columns must assign the
            // real index explicitly before writing the cell.
            var current = reader.Current;
            var values = new object?[columns.Count];
            if (current.IsDocument)
            {
                foreach (var field in current.AsDocument)
                {
                    var cell = ToCellValue(field.Value);
                    if (!columnLookup.TryGetValue(field.Key, out var index))
                    {
                        index = columns.Count;
                        columnLookup[field.Key] = index;
                        columns.Add(new ColumnInfo(field.Key, cell.TypeName, true, columns.Count + 1));
                        if (columns.Count > values.Length)
                        {
                            Array.Resize(ref values, columns.Count);
                        }
                    }
                    else
                    {
                        columns[index] = columns[index] with
                        {
                            TypeName = MergeType(columns[index].TypeName, cell.TypeName),
                        };
                    }
                    values[index] = cell.Value;
                }
            }
            else
            {
                var cell = ToCellValue(current);
                if (!columnLookup.TryGetValue("value", out var scalarIndex))
                {
                    scalarIndex = columns.Count;
                    columnLookup["value"] = scalarIndex;
                    columns.Add(new ColumnInfo("value", cell.TypeName, true, columns.Count + 1));
                    if (columns.Count > values.Length)
                    {
                        Array.Resize(ref values, columns.Count);
                    }
                }
                else
                {
                    columns[scalarIndex] = columns[scalarIndex] with
                    {
                        TypeName = MergeType(columns[scalarIndex].TypeName, cell.TypeName),
                    };
                }
                values[scalarIndex] = cell.Value;
            }
            batchRows.Add(values);
            totalRows++;

            if (maxRows > 0 && totalRows >= maxRows)
            {
                break;
            }

            if (batchRows.Count >= chunkSize)
            {
                yield return FlushChunk(columns, batchRows, done: false, totalRows, sourceCollections);
                batchRows = new List<object?[]>();
            }
        }

        yield return FlushChunk(columns, batchRows, done: true, totalRows, sourceCollections);
    }

    private static ExecuteChunk FlushChunk(
        List<ColumnInfo> columns,
        List<object?[]> batchRows,
        bool done,
        int totalRows,
        HashSet<string> sourceCollections)
    {
        var width = columns.Count;
        var rows = new object?[batchRows.Count][];
        for (var i = 0; i < batchRows.Count; i++)
        {
            var source = batchRows[i];
            var padded = new object?[width];
            Array.Copy(source, padded, Math.Min(source.Length, width));
            rows[i] = padded;
        }

        return new ExecuteChunk(
            done,
            columns.ToArray(),
            rows,
            done ? totalRows : 0,
            done ? sourceCollections.OrderBy(item => item, StringComparer.OrdinalIgnoreCase).ToArray() : Array.Empty<string>());
    }

    private object Collections()
    {
        return GetCatalog()
            .Select(item => new Dictionary<string, object?>
            {
                ["name"] = item.Name,
                ["type"] = item.Type.Equals("system", StringComparison.OrdinalIgnoreCase) ? "SYSTEM TABLE" : "TABLE",
                ["remarks"] = item.Type.Equals("system", StringComparison.OrdinalIgnoreCase) ? "LiteDB system collection" : "LiteDB collection",
            })
            .OrderBy(item => (string)item["name"]!, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private object Columns(JsonElement parameters)
    {
        var collectionFilter = OptionalString(parameters, "collection");
        var sampleSize = Math.Clamp(OptionalInt(parameters, "sampleSize") ?? 200, 1, 5000);
        var memoKey = (collectionFilter ?? string.Empty, sampleSize);
        if (columnsMemo.TryGetValue(memoKey, out var cached) && MemoFileUnchanged())
        {
            return cached;
        }

        var rows = new List<ColumnMetadata>();
        foreach (var collection in GetCatalog().Where(item =>
                     string.IsNullOrWhiteSpace(collectionFilter)
                     || item.Name.Equals(collectionFilter, StringComparison.OrdinalIgnoreCase)))
        {
            rows.AddRange(InferColumns(collection.Name, collection.Type, sampleSize));
        }

        var result = rows.ToArray();
        if (!string.IsNullOrEmpty(_databasePath))
        {
            var info = new FileInfo(_databasePath);
            memoFileStampUtc = info.Exists ? info.LastWriteTimeUtc : DateTime.MinValue;
            memoFileLength = info.Exists ? info.Length : -1;
        }
        columnsMemo[memoKey] = result;
        return result;
    }

    private object Indexes(JsonElement parameters)
    {
        var collectionFilter = OptionalString(parameters, "collection");
        var rows = new List<Dictionary<string, object?>>();

        using var reader = RequireDatabase().Execute("select $ from $indexes");
        while (reader.Read())
        {
            var doc = reader.Current.AsDocument;
            var collection = doc.TryGetValue("collection", out var collectionValue) ? collectionValue.AsString : string.Empty;
            if (!string.IsNullOrWhiteSpace(collectionFilter)
                && !collection.Equals(collectionFilter, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            rows.AddRange(ParseIndexRows(doc));
        }

        return rows;
    }

    /// <summary>
    /// Expands one $indexes row into per-column rows (JDBC getIndexInfo wants one row
    /// per column with ORDINAL_POSITION and ASC_OR_DESC). Composite expressions such as
    /// "($.a ASC, $.b DESC)" are split on top-level commas.
    /// </summary>
    private static List<Dictionary<string, object?>> ParseIndexRows(BsonDocument doc)
    {
        var expression = doc.TryGetValue("expression", out var expressionValue) ? expressionValue.AsString : string.Empty;
        var indexName = doc.TryGetValue("name", out var name) ? name.AsString : string.Empty;
        var collection = doc.TryGetValue("collection", out var collectionValue) ? collectionValue.AsString : string.Empty;
        var isUnique = doc.TryGetValue("unique", out var unique) && unique.AsBoolean;

        var columns = new List<(string Column, string Direction)>();
        var text = expression.Trim();
        if (text.StartsWith('(') && text.EndsWith(')'))
        {
            text = text[1..^1].Trim();
        }

        var depth = 0;
        var start = 0;
        for (var i = 0; i <= text.Length; i++)
        {
            var atEnd = i == text.Length;
            if (!atEnd)
            {
                var c = text[i];
                if (c == '(') { depth++; continue; }
                if (c == ')') { depth = Math.Max(0, depth - 1); continue; }
                if (c != ',' || depth != 0) { continue; }
            }
            var part = text[start..(atEnd ? text.Length : i)].Trim();
            start = i + 1;
            if (part.Length == 0) { continue; }
            var direction = "A";
            if (part.EndsWith(" ASC", StringComparison.OrdinalIgnoreCase)
                || part.EndsWith(" DESC", StringComparison.OrdinalIgnoreCase))
            {
                var space = part.LastIndexOf(' ');
                direction = part[(space + 1)..].Equals("DESC", StringComparison.OrdinalIgnoreCase) ? "D" : "A";
                part = part[..space].Trim();
            }
            var column = NormalizeIndexColumn(part);
            if (!string.IsNullOrWhiteSpace(column))
            {
                columns.Add((column, direction));
            }
        }

        var rows = new List<Dictionary<string, object?>>();
        var ordinal = 0;
        foreach (var (column, direction) in columns)
        {
            ordinal++;
            rows.Add(new Dictionary<string, object?>
            {
                ["collection"] = collection,
                ["name"] = indexName,
                ["expression"] = expression,
                ["column"] = column,
                ["unique"] = isUnique,
                ["ordinal"] = ordinal,
                ["direction"] = direction,
            });
        }
        return rows;
    }

    /// <summary>Accepts an inline JSON document either as a JSON string or as a raw JSON object.</summary>
    private static BsonValue ParseFlexibleJson(JsonElement element)
    {
        return element.ValueKind == JsonValueKind.String
            ? LiteDbJsonSerializer.Deserialize(element.GetString()!)
            : LiteDbJsonSerializer.Deserialize(element.GetRawText());
    }

    private object InsertDocument(JsonElement parameters)
    {
        var collection = RequiredString(parameters, "collection");
        var document = ParseFlexibleJson(parameters.GetProperty("document"));
        RequireDatabase().GetCollection(collection).Insert(document.AsDocument);
        return new Dictionary<string, object?> { ["inserted"] = true };
    }

    /// <summary>
    /// Read-modify-write partial update: fields not present in the change document are
    /// preserved (the JDBC result set only carries the columns it selected).
    /// </summary>
    private object UpdateDocument(JsonElement parameters)
    {
        var collection = RequiredString(parameters, "collection");
        var db = RequireDatabase();
        var id = ParseFlexibleJson(parameters.GetProperty("id"));
        var changes = ParseFlexibleJson(parameters.GetProperty("document")).AsDocument;

        var target = db.GetCollection(collection);
        var current = target.FindById(id);
        if (current is null)
        {
            return new Dictionary<string, object?> { ["updated"] = false };
        }

        foreach (var field in changes)
        {
            current[field.Key] = field.Value;
        }

        return new Dictionary<string, object?> { ["updated"] = target.Update(current) };
    }

    private object DeleteDocuments(JsonElement parameters)
    {
        var collection = RequiredString(parameters, "collection");
        var ids = parameters.GetProperty("ids").EnumerateArray().Select(ToBsonValue).ToArray();
        var deleted = RequireDatabase().GetCollection(collection).DeleteMany(Query.In("_id", ids));
        return new Dictionary<string, object?> { ["deleted"] = deleted };
    }

    private object Begin()
    {
        RequireDatabase().BeginTrans();
        return new Dictionary<string, object?> { ["transaction"] = true };
    }

    private object Commit()
    {
        RequireDatabase().Commit();
        return new Dictionary<string, object?> { ["transaction"] = false };
    }

    private object Rollback()
    {
        RequireDatabase().Rollback();
        return new Dictionary<string, object?> { ["transaction"] = false };
    }

    private IReadOnlyList<(string Name, string Type)> GetCatalog()
    {
        using var reader = RequireDatabase().Execute("select $ from $cols");
        var items = new List<(string Name, string Type)>();
        while (reader.Read())
        {
            var doc = reader.Current.AsDocument;
            var name = doc.TryGetValue("name", out var nameValue) ? nameValue.AsString : string.Empty;
            var type = doc.TryGetValue("type", out var typeValue) ? typeValue.AsString : "user";
            if (!string.IsNullOrWhiteSpace(name))
            {
                items.Add((name, type));
            }
        }

        return items;
    }

    private IReadOnlyList<ColumnMetadata> InferColumns(string collectionName, string collectionType, int sampleSize)
    {
        var db = RequireDatabase();
        var accumulators = new Dictionary<string, ColumnAccumulator>();
        var documents = 0;

        IEnumerable<BsonDocument> source;
        if (collectionType.Equals("system", StringComparison.OrdinalIgnoreCase))
        {
            source = ReadSystemCollection(collectionName, sampleSize);
        }
        else
        {
            source = db.GetCollection(collectionName).FindAll().Take(sampleSize);
        }

        try
        {
            foreach (var document in source)
            {
                documents++;
                foreach (var field in document)
                {
                    var accumulator = GetColumnAccumulator(accumulators, field.Key);
                    accumulator.TypeName = MergeType(accumulator.TypeName, GetTypeName(field.Value));
                    accumulator.Seen++;
                }
            }
        }
        catch (Exception) when (collectionType.Equals("system", StringComparison.OrdinalIgnoreCase))
        {
            // Some virtual system collections (for example $file) require parameters.
            // Keep metadata browsing resilient by falling back to a minimal _id column.
        }

        if (accumulators.Count == 0)
        {
            accumulators["_id"] = new ColumnAccumulator
            {
                Name = "_id",
                Ordinal = 1,
                TypeName = "VARCHAR",
                Seen = 0,
            };
        }

        return accumulators.Values
            .OrderBy(item => item.Ordinal)
            .Select(item => new ColumnMetadata(
                collectionName,
                item.Name,
                string.IsNullOrWhiteSpace(item.TypeName) ? "VARCHAR" : item.TypeName,
                documents == 0 || item.Seen < documents,
                item.Ordinal))
            .ToArray();
    }

    private IEnumerable<BsonDocument> ReadSystemCollection(string collectionName, int sampleSize)
    {
        using var reader = RequireDatabase().Execute($"select $ from {collectionName}");
        var count = 0;
        while (reader.Read())
        {
            if (reader.Current.IsDocument)
            {
                yield return reader.Current.AsDocument;
            }

            count++;
            if (count >= sampleSize)
            {
                yield break;
            }
        }
    }

    private static ColumnAccumulator GetColumnAccumulator(IDictionary<string, ColumnAccumulator> accumulators, string name)
    {
        if (accumulators.TryGetValue(name, out var accumulator))
        {
            return accumulator;
        }

        accumulator = new ColumnAccumulator
        {
            Name = name,
            Ordinal = accumulators.Count + 1,
            TypeName = "NULL",
        };
        accumulators[name] = accumulator;
        return accumulator;
    }

    private static Dictionary<string, CellValue> ExtractCells(BsonValue value)
    {
        // Case-sensitive: LiteDB field names distinguish case; merging "Name" and "name"
        // into one column would silently overwrite data.
        var cells = new Dictionary<string, CellValue>();
        if (value.IsDocument)
        {
            foreach (var field in value.AsDocument)
            {
                cells[field.Key] = ToCellValue(field.Value);
            }
        }
        else
        {
            cells["value"] = ToCellValue(value);
        }

        return cells;
    }

    private static CellValue ToCellValue(BsonValue value)
    {
        return new CellValue(ToJsonValue(value), GetTypeName(value));
    }

    private static object? ToJsonValue(BsonValue value)
    {
        if (value.IsNull || value.Type == BsonType.MinValue || value.Type == BsonType.MaxValue)
        {
            return null;
        }

        return value.Type switch
        {
            BsonType.String => value.AsString,
            BsonType.Int32 => value.AsInt32,
            BsonType.Int64 => value.AsInt64,
            BsonType.Double => double.IsFinite(value.AsDouble) ? value.AsDouble : null,
            BsonType.Decimal => value.AsDecimal,
            BsonType.Boolean => value.AsBoolean,
            BsonType.DateTime => value.AsDateTime.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture),
            BsonType.ObjectId => value.AsObjectId.ToString(),
            BsonType.Guid => value.AsGuid.ToString(),
            BsonType.Binary => Convert.ToBase64String(value.AsBinary),
            // MinValue/MaxValue are first-class comparable sentinels; expose them as the
            // official extended-JSON spellings instead of collapsing them into NULL.
            BsonType.MinValue => "{\"$minValue\":\"1\"}",
            BsonType.MaxValue => "{\"$maxValue\":\"1\"}",
            BsonType.Document or BsonType.Array => LiteDbJsonSerializer.Serialize(value, false),
            _ => value.ToString(),
        };
    }

    private static string GetTypeName(BsonValue value)
    {
        return value.Type switch
        {
            BsonType.Null => "NULL",
            BsonType.MinValue or BsonType.MaxValue => "VARCHAR",
            BsonType.String => "VARCHAR",
            BsonType.Int32 => "INTEGER",
            BsonType.Int64 => "BIGINT",
            BsonType.Double => "DOUBLE",
            BsonType.Decimal => "DECIMAL",
            BsonType.Boolean => "BOOLEAN",
            BsonType.DateTime => "TIMESTAMP",
            BsonType.ObjectId => "OBJECT_ID",
            BsonType.Guid => "GUID",
            BsonType.Binary => "BINARY",
            BsonType.Document or BsonType.Array => "JSON",
            _ => "VARCHAR",
        };
    }

    private static string MergeType(string current, string next)
    {
        if (string.IsNullOrWhiteSpace(current) || current == "NULL")
        {
            return next;
        }

        if (string.IsNullOrWhiteSpace(next) || next == "NULL" || current.Equals(next, StringComparison.OrdinalIgnoreCase))
        {
            return current;
        }

        var numeric = new[] { "INTEGER", "BIGINT", "DOUBLE", "DECIMAL" };
        if (numeric.Contains(current) && numeric.Contains(next))
        {
            if (current == "DECIMAL" || next == "DECIMAL")
            {
                return "DECIMAL";
            }

            if (current == "DOUBLE" || next == "DOUBLE")
            {
                return "DOUBLE";
            }

            return "BIGINT";
        }

        return "VARCHAR";
    }

    private static string NormalizeIndexColumn(string expression)
    {
        var text = expression.Trim();
        while (text.StartsWith('$'))
        {
            text = text[1..].TrimStart('.');
        }

        return string.IsNullOrWhiteSpace(text) ? expression : text.Trim('"', '\'', '`');
    }

    private static BsonValue[] ReadParameters(JsonElement parameters)
    {
        if (!parameters.TryGetProperty("parameters", out var values) || values.ValueKind != JsonValueKind.Array)
        {
            return Array.Empty<BsonValue>();
        }

        return values.EnumerateArray().Select(ToBsonValue).ToArray();
    }

    private static BsonValue ToBsonValue(JsonElement value)
    {
        return value.ValueKind switch
        {
            JsonValueKind.Null or JsonValueKind.Undefined => BsonValue.Null,
            JsonValueKind.String => value.GetString(),
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number when value.TryGetInt32(out var intValue) => intValue,
            JsonValueKind.Number when value.TryGetInt64(out var longValue) => longValue,
            JsonValueKind.Number when value.TryGetDecimal(out var decimalValue) => decimalValue,
            JsonValueKind.Object or JsonValueKind.Array => LiteDbJsonSerializer.Deserialize(value.GetRawText()),
            _ => value.GetRawText(),
        };
    }

    private LiteDatabase RequireDatabase()
    {
        return _database ?? throw new InvalidOperationException("No LiteDB database is open.");
    }

    private static string RequiredString(JsonElement parameters, string name)
    {
        var value = OptionalString(parameters, name);
        return string.IsNullOrWhiteSpace(value)
            ? throw new InvalidOperationException($"Missing required parameter '{name}'.")
            : value;
    }

    private static string? OptionalString(JsonElement parameters, string name)
    {
        if (parameters.ValueKind != JsonValueKind.Object || !parameters.TryGetProperty(name, out var value))
        {
            return null;
        }

        return value.ValueKind == JsonValueKind.String ? value.GetString() : value.ToString();
    }

    private static int? OptionalInt(JsonElement parameters, string name)
    {
        if (parameters.ValueKind != JsonValueKind.Object || !parameters.TryGetProperty(name, out var value))
        {
            return null;
        }

        return value.ValueKind switch
        {
            JsonValueKind.Number when value.TryGetInt32(out var intValue) => intValue,
            JsonValueKind.String when int.TryParse(value.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var intValue) => intValue,
            _ => null,
        };
    }

    private static bool? OptionalBool(JsonElement parameters, string name)
    {
        if (parameters.ValueKind != JsonValueKind.Object || !parameters.TryGetProperty(name, out var value))
        {
            return null;
        }

        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.String when bool.TryParse(value.GetString(), out var boolValue) => boolValue,
            _ => null,
        };
    }

    private static ConnectionType ParseConnectionType(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return ConnectionType.Shared;
        }

        return Enum.TryParse<ConnectionType>(value.Trim(), true, out var connectionType)
            ? connectionType
            : ConnectionType.Shared;
    }

    private static string CreateOpenError(string databasePath, string? password, Exception ex)
    {
        var message = $"Failed to open LiteDB database '{databasePath}': {EnsureSentence(ex.Message)}";
        if (LooksLikePasswordError(ex) || string.IsNullOrWhiteSpace(password))
        {
            message += " If this database is encrypted, enter the LiteDB password in DBeaver's password field or set the JDBC property 'password'.";
        }

        return message;
    }

    private static string EnsureSentence(string value)
    {
        var text = value.Trim();
        return text.EndsWith('.') || text.EndsWith('!') || text.EndsWith('?') ? text : text + ".";
    }

    private static bool LooksLikePasswordError(Exception ex)
    {
        for (var current = ex; current is not null; current = current.InnerException)
        {
            if (current.Message.Contains("password", StringComparison.OrdinalIgnoreCase)
                || current.Message.Contains("encrypted", StringComparison.OrdinalIgnoreCase)
                || current.Message.Contains("decrypt", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }

    private static void WriteResponse(RpcResponse response)
    {
        Console.Out.WriteLine(System.Text.Json.JsonSerializer.Serialize(response, JsonOptions));
        Console.Out.Flush();
    }

    /// <summary>
    /// Handwritten fast path for execute chunks: a Utf8JsonWriter pass replaces the
    /// reflection-based serializer's per-cell runtime type dispatch, and the output
    /// is byte-compatible with the generic shape (camelCase field names, null cells,
    /// plain decimal/long/integer numbers). The produced JSON is written as one line
    /// followed by an explicit flush - the line protocol depends on both.
    /// </summary>
    private static void WriteChunkResponse(long id, ExecuteChunk chunk)
    {
        var buffer = new System.Buffers.ArrayBufferWriter<byte>();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteNumber("id", id);
            writer.WriteBoolean("ok", true);
            writer.WritePropertyName("result");
            writer.WriteStartObject();
            writer.WriteBoolean("done", chunk.Done);
            writer.WritePropertyName("columns");
            writer.WriteStartArray();
            foreach (var column in chunk.Columns)
            {
                writer.WriteStartObject();
                writer.WriteString("name", column.Name);
                writer.WriteString("typeName", column.TypeName);
                writer.WriteBoolean("nullable", column.Nullable);
                writer.WriteNumber("ordinal", column.Ordinal);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
            writer.WritePropertyName("rows");
            writer.WriteStartArray();
            foreach (var row in chunk.Rows)
            {
                writer.WriteStartArray();
                foreach (var cell in row)
                {
                    switch (cell)
                    {
                        case null:
                            writer.WriteNullValue();
                            break;
                        case bool flag:
                            writer.WriteBooleanValue(flag);
                            break;
                        case int intValue:
                            writer.WriteNumberValue(intValue);
                            break;
                        case long longValue:
                            writer.WriteNumberValue(longValue);
                            break;
                        case decimal decimalValue:
                            writer.WriteNumberValue(decimalValue);
                            break;
                        case double doubleValue:
                            writer.WriteNumberValue(doubleValue);
                            break;
                        case float floatValue:
                            writer.WriteNumberValue(floatValue);
                            break;
                        default:
                            writer.WriteStringValue(cell.ToString());
                            break;
                    }
                }
                writer.WriteEndArray();
            }
            writer.WriteEndArray();
            writer.WriteNumber("rowCount", chunk.RowCount);
            writer.WritePropertyName("sourceCollections");
            writer.WriteStartArray();
            foreach (var sourceCollection in chunk.SourceCollections)
            {
                writer.WriteStringValue(sourceCollection);
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
            writer.WriteEndObject();
        }

        Console.Out.Write(Encoding.UTF8.GetString(buffer.WrittenSpan));
        Console.Out.Write("\n");
        Console.Out.Flush();
    }

    private sealed class ColumnAccumulator
    {
        public required string Name { get; init; }

        public required int Ordinal { get; init; }

        public required string TypeName { get; set; }

        public int Seen { get; set; }
    }
}

internal static class BridgeRuntime
{
    public static object GetInfo(string? databasePath, bool readOnly, ConnectionType connectionType = ConnectionType.Shared)
    {
        var version = typeof(LiteDatabase).Assembly.GetName().Version?.ToString() ?? "unknown";
        return new Dictionary<string, object?>
        {
            ["bridgeVersion"] = typeof(BridgeRuntime).Assembly.GetName().Version?.ToString() ?? "dev",
            ["liteDbVersion"] = version,
            ["databasePath"] = databasePath,
            ["readOnly"] = readOnly,
            ["connectionMode"] = connectionType.ToString(),
        };
    }

    public static string CreateVersionText()
    {
        var info = (Dictionary<string, object?>)GetInfo(null, false);
        return $"LiteDbJdbc.Bridge {info["bridgeVersion"]}; LiteDB {info["liteDbVersion"]}";
    }
}

internal sealed record RpcRequest(long Id, string? Method, JsonElement Params);

internal sealed record RpcResponse(long Id, bool Ok, object? Result, RpcError? Error);

internal sealed record RpcError(string Type, string Message);

internal sealed record ColumnInfo(string Name, string TypeName, bool Nullable, int Ordinal);

internal sealed record ColumnMetadata(string Collection, string Name, string TypeName, bool Nullable, int Ordinal);

internal sealed record CellValue(object? Value, string TypeName);

/// <summary>
/// One bounded batch of an execute result stream. The final chunk (done=true)
/// carries the total row count and the source collections.
/// </summary>
internal sealed record ExecuteChunk(
    bool Done,
    ColumnInfo[] Columns,
    object?[][] Rows,
    int RowCount,
    string[] SourceCollections);
