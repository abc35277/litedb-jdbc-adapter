using LiteDB;

if (args.Length is < 1 or > 2)
{
    Console.Error.WriteLine("Usage: LiteDbJdbc.SampleDb <database-file> [password]");
    return 2;
}

var path = Path.GetFullPath(args[0]);
var password = args.Length >= 2 && !string.IsNullOrWhiteSpace(args[1]) ? args[1] : null;
Directory.CreateDirectory(Path.GetDirectoryName(path)!);
if (File.Exists(path))
{
    File.Delete(path);
}

var connectionString = new ConnectionString
{
    Filename = path,
    Password = password,
    Connection = ConnectionType.Shared,
};

using var database = new LiteDatabase(connectionString);
var people = database.GetCollection("people");
people.Insert(new BsonDocument
{
    ["name"] = "Ada",
    ["age"] = 37,
    ["active"] = true,
    ["city"] = "London",
});
people.Insert(new BsonDocument
{
    ["name"] = "Linus",
    ["age"] = 56,
    ["active"] = true,
    ["city"] = "Helsinki",
});
people.EnsureIndex("idx_name", "$.name", false);

var orders = database.GetCollection("orders");
orders.Insert(new BsonDocument
{
    ["customer"] = "Ada",
    ["total"] = 128.50m,
    ["createdAt"] = DateTime.UtcNow,
});

database.Checkpoint();
Console.WriteLine(path);
return 0;
