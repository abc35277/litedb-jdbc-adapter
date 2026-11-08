using LiteDB;

if (args.Length is < 1 or > 2)
{
    Console.Error.WriteLine("Usage: LiteDbJdbc.ComplexDb <database-file> [password]");
    return 2;
}

var path = Path.GetFullPath(args[0]);
var password = args.Length == 2 && !string.IsNullOrWhiteSpace(args[1]) ? args[1] : null;
var directory = Path.GetDirectoryName(path);
if (!string.IsNullOrWhiteSpace(directory))
{
    Directory.CreateDirectory(directory);
}

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
var baseDate = DateTime.SpecifyKind(new DateTime(2026, 5, 1, 8, 0, 0), DateTimeKind.Utc);
var random = new Random(2601);

var suppliers = CreateSuppliers();
Insert(database, "suppliers", suppliers, collection =>
{
    collection.EnsureIndex("ux_supplier_code", "$.code", true);
    collection.EnsureIndex("ix_supplier_region", "$.region", false);
});

var warehouses = CreateWarehouses();
Insert(database, "warehouses", warehouses, collection =>
{
    collection.EnsureIndex("ux_warehouse_code", "$.code", true);
    collection.EnsureIndex("ix_warehouse_city", "$.address.city", false);
});

var customers = CreateCustomers(baseDate);
Insert(database, "customers", customers, collection =>
{
    collection.EnsureIndex("ux_customer_code", "$.code", true);
    collection.EnsureIndex("ix_customer_tier", "$.tier", false);
    collection.EnsureIndex("ix_customer_city", "$.address.city", false);
    collection.EnsureIndex("ix_customer_created", "$.createdAt", false);
});

var products = CreateProducts(suppliers, baseDate);
Insert(database, "products", products, collection =>
{
    collection.EnsureIndex("ux_product_sku", "$.sku", true);
    collection.EnsureIndex("ix_product_category", "$.category", false);
    collection.EnsureIndex("ix_product_supplier", "$.supplierId", false);
    collection.EnsureIndex("ix_product_price", "$.price", false);
});

var orders = new List<BsonDocument>();
var orderItems = new List<BsonDocument>();
var payments = new List<BsonDocument>();
var shipments = new List<BsonDocument>();
CreateOrders(customers, products, warehouses, baseDate, random, orders, orderItems, payments, shipments);

Insert(database, "orders", orders, collection =>
{
    collection.EnsureIndex("ux_order_no", "$.orderNo", true);
    collection.EnsureIndex("ix_order_customer", "$.customerId", false);
    collection.EnsureIndex("ix_order_status", "$.status", false);
    collection.EnsureIndex("ix_order_date", "$.orderDate", false);
});

Insert(database, "order_items", orderItems, collection =>
{
    collection.EnsureIndex("ix_item_order", "$.orderId", false);
    collection.EnsureIndex("ix_item_sku", "$.sku", false);
    collection.EnsureIndex("ix_item_warehouse", "$.warehouseId", false);
});

Insert(database, "payments", payments, collection =>
{
    collection.EnsureIndex("ix_payment_order", "$.orderId", false);
    collection.EnsureIndex("ix_payment_customer", "$.customerId", false);
    collection.EnsureIndex("ix_payment_paid_at", "$.paidAt", false);
});

Insert(database, "shipments", shipments, collection =>
{
    collection.EnsureIndex("ix_shipment_order", "$.orderId", false);
    collection.EnsureIndex("ix_shipment_status", "$.status", false);
    collection.EnsureIndex("ix_shipment_tracking", "$.trackingNo", true);
});

var inventoryMovements = CreateInventoryMovements(products, warehouses, baseDate, random);
Insert(database, "inventory_movements", inventoryMovements, collection =>
{
    collection.EnsureIndex("ix_inventory_sku", "$.sku", false);
    collection.EnsureIndex("ix_inventory_warehouse", "$.warehouseId", false);
    collection.EnsureIndex("ix_inventory_created", "$.createdAt", false);
});

var tickets = CreateSupportTickets(customers, orders, baseDate, random);
Insert(database, "support_tickets", tickets, collection =>
{
    collection.EnsureIndex("ix_ticket_customer", "$.customerId", false);
    collection.EnsureIndex("ix_ticket_status", "$.status", false);
    collection.EnsureIndex("ix_ticket_priority", "$.priority", false);
});

var readings = CreateSensorReadings(warehouses, baseDate, random);
Insert(database, "sensor_readings", readings, collection =>
{
    collection.EnsureIndex("ix_sensor_device", "$.deviceId", false);
    collection.EnsureIndex("ix_sensor_time", "$.capturedAt", false);
});

var auditLogs = CreateAuditLogs(customers, orders, products, baseDate, random);
Insert(database, "audit_logs", auditLogs, collection =>
{
    collection.EnsureIndex("ix_audit_entity", "$.entity", false);
    collection.EnsureIndex("ix_audit_entity_id", "$.entityId", false);
    collection.EnsureIndex("ix_audit_created", "$.createdAt", false);
});

Insert(database, "field_types", CreateFieldTypes(baseDate), collection =>
{
    collection.EnsureIndex("ix_field_types_label", "$.label", false);
});

Insert(database, "app_settings", CreateAppSettings(baseDate), collection =>
{
    collection.EnsureIndex("ux_setting_key", "$.key", true);
});

// An empty collection with only an index is useful to verify metadata behavior without sampled rows.
database.GetCollection("empty_collection").EnsureIndex("ix_placeholder", "$.placeholder", false);

database.Checkpoint();

Console.WriteLine(path);
Console.WriteLine($"encrypted={(!string.IsNullOrWhiteSpace(password)).ToString().ToLowerInvariant()}");
Console.WriteLine("collections=customers,suppliers,products,warehouses,orders,order_items,payments,shipments,inventory_movements,support_tickets,sensor_readings,audit_logs,field_types,app_settings,empty_collection");
Console.WriteLine($"customers={customers.Count}; products={products.Count}; orders={orders.Count}; order_items={orderItems.Count}; payments={payments.Count}; audit_logs={auditLogs.Count}; sensor_readings={readings.Count}");
return 0;

static void Insert(LiteDatabase database, string name, List<BsonDocument> documents, Action<ILiteCollection<BsonDocument>> indexes)
{
    var collection = database.GetCollection(name);
    if (documents.Count > 0)
    {
        collection.InsertBulk(documents);
    }

    indexes(collection);
}

static List<BsonDocument> CreateSuppliers()
{
    var data = new[]
    {
        ("SUP-CN-01", "上海东方电子", "CN-East", "electronics", "CN"),
        ("SUP-CN-02", "深圳云栈科技", "CN-South", "iot", "CN"),
        ("SUP-EU-01", "Nordwind Components", "EU-North", "hardware", "DE"),
        ("SUP-US-01", "Pacific Retail Labs", "US-West", "retail", "US"),
        ("SUP-JP-01", "Sakura Sensor Works", "APAC", "sensor", "JP"),
        ("SUP-IN-01", "Banyan Office Supply", "APAC", "office", "IN"),
    };

    var result = new List<BsonDocument>();
    for (var i = 0; i < data.Length; i++)
    {
        var item = data[i];
        result.Add(new BsonDocument
        {
            ["_id"] = item.Item1,
            ["code"] = item.Item1,
            ["name"] = item.Item2,
            ["region"] = item.Item3,
            ["category"] = item.Item4,
            ["country"] = item.Item5,
            ["active"] = i != 4,
            ["rating"] = Math.Round(3.9 + i * 0.17, 2),
            ["contact"] = new BsonDocument
            {
                ["name"] = $"Contact {i + 1}",
                ["email"] = $"supplier{i + 1}@example.test",
                ["phone"] = $"+86-021-55{i:0000}",
            },
            ["certifications"] = new BsonArray { "ISO9001", i % 2 == 0 ? "RoHS" : "CE" },
            ["settlement"] = new BsonDocument
            {
                ["currency"] = i % 2 == 0 ? "CNY" : "USD",
                ["paymentTermDays"] = 30 + i * 5,
            },
        });
    }

    return result;
}

static List<BsonDocument> CreateWarehouses()
{
    var data = new[]
    {
        ("WH-SH", "上海主仓", "Shanghai", "CN", 121.4737, 31.2304),
        ("WH-SZ", "深圳前海仓", "Shenzhen", "CN", 114.0579, 22.5431),
        ("WH-BJ", "北京北区仓", "Beijing", "CN", 116.4074, 39.9042),
        ("WH-CD", "成都西南仓", "Chengdu", "CN", 104.0668, 30.5728),
    };

    return data.Select((item, index) => new BsonDocument
    {
        ["_id"] = item.Item1,
        ["code"] = item.Item1,
        ["name"] = item.Item2,
        ["capacity"] = 8000 + index * 2500,
        ["temperatureControlled"] = index is 1 or 3,
        ["address"] = new BsonDocument
        {
            ["city"] = item.Item3,
            ["country"] = item.Item4,
            ["line1"] = $"测试路 {index + 1} 号",
            ["geo"] = new BsonDocument { ["lng"] = item.Item5, ["lat"] = item.Item6 },
        },
        ["zones"] = new BsonArray
        {
            new BsonDocument { ["code"] = "A", ["type"] = "normal", ["bins"] = 120 + index * 10 },
            new BsonDocument { ["code"] = "B", ["type"] = index % 2 == 0 ? "bulk" : "cold", ["bins"] = 80 + index * 8 },
        },
    }).ToList();
}

static List<BsonDocument> CreateCustomers(DateTime baseDate)
{
    var names = new[]
    {
        "杭州星河制造", "北京青云医疗", "成都锦里餐饮", "上海赛博零售", "广州南站物流", "南京玄武教育",
        "苏州工业园区自动化", "重庆山城能源", "武汉江汉实验室", "厦门海风贸易", "天津滨海港务", "西安长安文旅",
    };
    var cities = new[] { "Hangzhou", "Beijing", "Chengdu", "Shanghai", "Guangzhou", "Nanjing", "Suzhou", "Chongqing", "Wuhan", "Xiamen", "Tianjin", "Xi'an" };
    var tiers = new[] { "gold", "silver", "bronze", "platinum" };

    var result = new List<BsonDocument>();
    for (var i = 0; i < names.Length; i++)
    {
        var code = $"CUST-{1001 + i}";
        result.Add(new BsonDocument
        {
            ["_id"] = code,
            ["code"] = code,
            ["name"] = names[i],
            ["shortName"] = names[i][..Math.Min(4, names[i].Length)],
            ["tier"] = tiers[i % tiers.Length],
            ["active"] = i % 7 != 0,
            ["createdAt"] = baseDate.AddDays(-220 + i * 13),
            ["lastLoginAt"] = i % 4 == 0 ? BsonValue.Null : baseDate.AddHours(-i * 9),
            ["creditLimit"] = 20000m + i * 7500m,
            ["balance"] = Math.Round(1250.35m + i * 432.17m, 2),
            ["riskScore"] = Math.Round(0.12 + (i % 6) * 0.13, 3),
            ["tags"] = new BsonArray { i % 2 == 0 ? "重点客户" : "普通客户", tiers[i % tiers.Length], i % 3 == 0 ? "needs-review" : "auto-approved" },
            ["address"] = new BsonDocument
            {
                ["country"] = "CN",
                ["province"] = i % 2 == 0 ? "Zhejiang" : "Jiangsu",
                ["city"] = cities[i],
                ["line1"] = $"业务测试大道 {88 + i} 号",
                ["postalCode"] = $"31{i:0000}",
            },
            ["contacts"] = new BsonArray
            {
                new BsonDocument { ["name"] = $"采购经理{i + 1}", ["role"] = "buyer", ["email"] = $"buyer{i + 1}@customer.test", ["primary"] = true },
                new BsonDocument { ["name"] = $"财务{i + 1}", ["role"] = "finance", ["email"] = $"finance{i + 1}@customer.test", ["primary"] = false },
            },
            ["preferences"] = new BsonDocument
            {
                ["invoiceType"] = i % 2 == 0 ? "VAT_SPECIAL" : "VAT_NORMAL",
                ["allowPartialShipment"] = i % 3 != 0,
                ["language"] = i % 5 == 0 ? "zh-CN" : "en-US",
            },
            ["remark"] = i % 3 == 0 ? "含中文、嵌套数组、空值、Decimal、DateTime 等复杂字段" : "standard customer",
        });
    }

    return result;
}

static List<BsonDocument> CreateProducts(List<BsonDocument> suppliers, DateTime baseDate)
{
    var categories = new[] { "electronics", "sensor", "office", "industrial", "software", "service" };
    var result = new List<BsonDocument>();
    for (var i = 0; i < 30; i++)
    {
        var category = categories[i % categories.Length];
        var sku = $"SKU-{category[..3].ToUpperInvariant()}-{2000 + i}";
        var price = Math.Round(19.9m + i * 7.37m + (i % 4) * 0.99m, 2);
        result.Add(new BsonDocument
        {
            ["_id"] = sku,
            ["sku"] = sku,
            ["name"] = category switch
            {
                "electronics" => $"工业平板电脑 {i + 1}",
                "sensor" => $"温湿度传感器 {i + 1}",
                "office" => $"办公耗材套装 {i + 1}",
                "industrial" => $"自动化控制模块 {i + 1}",
                "software" => $"边缘网关授权 {i + 1}",
                _ => $"现场安装服务 {i + 1}",
            },
            ["category"] = category,
            ["supplierId"] = suppliers[i % suppliers.Count]["_id"],
            ["price"] = price,
            ["cost"] = Math.Round(price * 0.62m, 2),
            ["currency"] = "CNY",
            ["stock"] = 20 + i * 11,
            ["reorderLevel"] = 15 + i % 8,
            ["discontinued"] = i % 13 == 0,
            ["releasedAt"] = baseDate.AddDays(-500 + i * 17),
            ["dimensions"] = new BsonDocument
            {
                ["widthMm"] = 80 + i,
                ["heightMm"] = 45 + i % 7,
                ["depthMm"] = 12 + i % 5,
                ["weightKg"] = Math.Round(0.35 + i * 0.08, 3),
            },
            ["attributes"] = new BsonDocument
            {
                ["color"] = i % 2 == 0 ? "black" : "silver",
                ["warrantyMonths"] = 12 + i % 4 * 6,
                ["hazardous"] = false,
                ["metadataVersion"] = 2,
            },
            ["tags"] = new BsonArray { category, i % 2 == 0 ? "hot" : "standard", i % 5 == 0 ? "促销" : "normal" },
        });
    }

    return result;
}

static void CreateOrders(
    List<BsonDocument> customers,
    List<BsonDocument> products,
    List<BsonDocument> warehouses,
    DateTime baseDate,
    Random random,
    List<BsonDocument> orders,
    List<BsonDocument> orderItems,
    List<BsonDocument> payments,
    List<BsonDocument> shipments)
{
    var statuses = new[] { "draft", "confirmed", "paid", "packed", "shipped", "delivered", "cancelled", "refunded" };
    var channels = new[] { "web", "dbeaver-test", "api", "import", "mobile" };
    var paymentMethods = new[] { "bank_transfer", "wechat", "alipay", "credit_card", "cash" };

    for (var i = 0; i < 48; i++)
    {
        var customer = customers[i % customers.Count];
        var status = statuses[i % statuses.Length];
        var orderId = $"ORD-2026-{i + 1:0000}";
        var orderDate = baseDate.AddDays(-60 + i).AddMinutes(i * 11);
        var lineCount = 2 + i % 4;
        var lines = new BsonArray();
        var subtotal = 0m;

        for (var line = 1; line <= lineCount; line++)
        {
            var product = products[(i * 3 + line * 5) % products.Count];
            var warehouse = warehouses[(i + line) % warehouses.Count];
            var qty = 1 + (i + line) % 8;
            var unitPrice = product["price"].AsDecimal;
            var amount = Math.Round(unitPrice * qty, 2);
            subtotal += amount;
            var itemId = $"{orderId}-{line:00}";
            var item = new BsonDocument
            {
                ["_id"] = itemId,
                ["orderId"] = orderId,
                ["lineNo"] = line,
                ["sku"] = product["sku"],
                ["productName"] = product["name"],
                ["category"] = product["category"],
                ["warehouseId"] = warehouse["_id"],
                ["quantity"] = qty,
                ["unitPrice"] = unitPrice,
                ["discountRate"] = Math.Round((i % 5) * 0.015m, 3),
                ["amount"] = amount,
                ["serials"] = new BsonArray { $"SN{i:0000}{line}A", $"SN{i:0000}{line}B" },
            };
            orderItems.Add(item);
            lines.Add(new BsonDocument
            {
                ["lineNo"] = line,
                ["sku"] = product["sku"],
                ["name"] = product["name"],
                ["qty"] = qty,
                ["unitPrice"] = unitPrice,
                ["amount"] = amount,
            });
        }

        var discount = Math.Round(subtotal * (i % 6 == 0 ? 0.08m : 0.02m), 2);
        var tax = Math.Round((subtotal - discount) * 0.13m, 2);
        var shippingFee = status == "cancelled" ? 0m : 18m + i % 5 * 3m;
        var grandTotal = subtotal - discount + tax + shippingFee;
        var paid = status is "paid" or "packed" or "shipped" or "delivered" or "refunded";
        var paidAt = paid ? orderDate.AddHours(2 + i % 5) : (DateTime?)null;

        orders.Add(new BsonDocument
        {
            ["_id"] = orderId,
            ["orderNo"] = orderId,
            ["customerId"] = customer["_id"],
            ["customerName"] = customer["name"],
            ["orderDate"] = orderDate,
            ["status"] = status,
            ["channel"] = channels[i % channels.Length],
            ["currency"] = "CNY",
            ["subtotal"] = subtotal,
            ["discount"] = discount,
            ["tax"] = tax,
            ["shippingFee"] = shippingFee,
            ["grandTotal"] = grandTotal,
            ["paid"] = paid,
            ["paidAt"] = paidAt.HasValue ? paidAt.Value : BsonValue.Null,
            ["couponCode"] = i % 7 == 0 ? $"PROMO-{i:00}" : BsonValue.Null,
            ["shipTo"] = customer["address"],
            ["lines"] = lines,
            ["workflow"] = new BsonArray
            {
                new BsonDocument { ["status"] = "created", ["at"] = orderDate, ["operator"] = "system" },
                new BsonDocument { ["status"] = status, ["at"] = orderDate.AddHours(1), ["operator"] = i % 2 == 0 ? "alice" : "bob" },
            },
            ["flags"] = new BsonDocument
            {
                ["requiresInvoice"] = i % 3 != 0,
                ["manualReview"] = i % 11 == 0,
                ["priority"] = i % 9 == 0 ? "high" : "normal",
            },
        });

        if (paid)
        {
            payments.Add(new BsonDocument
            {
                ["_id"] = $"PAY-{i + 1:0000}",
                ["orderId"] = orderId,
                ["customerId"] = customer["_id"],
                ["method"] = paymentMethods[i % paymentMethods.Length],
                ["amount"] = grandTotal,
                ["currency"] = "CNY",
                ["paidAt"] = paidAt!.Value,
                ["captured"] = status != "refunded",
                ["transactionId"] = Guid.NewGuid(),
                ["gatewayPayload"] = new BsonDocument
                {
                    ["traceId"] = $"TRACE-{i + 10000}",
                    ["risk"] = random.NextDouble(),
                    ["raw"] = new BsonDocument { ["bank"] = "TESTBANK", ["batch"] = i / 5 },
                },
            });
        }

        if (status is "shipped" or "delivered")
        {
            shipments.Add(new BsonDocument
            {
                ["_id"] = $"SHP-{i + 1:0000}",
                ["orderId"] = orderId,
                ["carrier"] = i % 2 == 0 ? "SF Express" : "JD Logistics",
                ["trackingNo"] = $"TRK{20260500 + i}",
                ["status"] = status == "delivered" ? "delivered" : "in_transit",
                ["shippedAt"] = orderDate.AddDays(1),
                ["deliveredAt"] = status == "delivered" ? orderDate.AddDays(3 + i % 3) : BsonValue.Null,
                ["packages"] = new BsonArray
                {
                    new BsonDocument { ["weightKg"] = Math.Round(1.2 + i * 0.05, 2), ["lengthCm"] = 40 + i % 8, ["fragile"] = i % 4 == 0 },
                },
            });
        }
    }
}

static List<BsonDocument> CreateInventoryMovements(List<BsonDocument> products, List<BsonDocument> warehouses, DateTime baseDate, Random random)
{
    var types = new[] { "IN", "OUT", "ADJUST", "TRANSFER", "RETURN" };
    var result = new List<BsonDocument>();
    for (var i = 0; i < 140; i++)
    {
        var product = products[i % products.Count];
        var warehouse = warehouses[(i * 2) % warehouses.Count];
        result.Add(new BsonDocument
        {
            ["_id"] = $"MOV-{i + 1:00000}",
            ["sku"] = product["sku"],
            ["warehouseId"] = warehouse["_id"],
            ["movementType"] = types[i % types.Length],
            ["quantity"] = (i % 2 == 0 ? 1 : -1) * (1 + random.Next(1, 30)),
            ["createdAt"] = baseDate.AddHours(-240 + i * 3),
            ["reason"] = i % 5 == 0 ? "cycle-count" : "normal-flow",
            ["operator"] = new BsonDocument { ["id"] = $"U{100 + i % 9}", ["name"] = i % 2 == 0 ? "张三" : "李四" },
            ["costSnapshot"] = product["cost"],
        });
    }

    return result;
}

static List<BsonDocument> CreateSupportTickets(List<BsonDocument> customers, List<BsonDocument> orders, DateTime baseDate, Random random)
{
    var priorities = new[] { "low", "medium", "high", "urgent" };
    var statuses = new[] { "open", "pending", "resolved", "closed" };
    var result = new List<BsonDocument>();
    for (var i = 0; i < 24; i++)
    {
        var customer = customers[i % customers.Count];
        var order = orders[(i * 2) % orders.Count];
        result.Add(new BsonDocument
        {
            ["_id"] = $"TCK-{i + 1:0000}",
            ["customerId"] = customer["_id"],
            ["orderId"] = order["_id"],
            ["subject"] = i % 3 == 0 ? "发票信息需要修正" : i % 3 == 1 ? "物流状态咨询" : "产品参数确认",
            ["status"] = statuses[i % statuses.Length],
            ["priority"] = priorities[i % priorities.Length],
            ["createdAt"] = baseDate.AddDays(-20 + i),
            ["slaHours"] = 4 + i % 5 * 4,
            ["satisfaction"] = i % 4 == 0 ? BsonValue.Null : Math.Round(3.5 + random.NextDouble() * 1.5, 2),
            ["comments"] = new BsonArray
            {
                new BsonDocument { ["by"] = "customer", ["at"] = baseDate.AddDays(-20 + i), ["text"] = "请帮忙确认，谢谢" },
                new BsonDocument { ["by"] = "agent", ["at"] = baseDate.AddDays(-20 + i).AddHours(2), ["text"] = "已收到，正在处理" },
            },
        });
    }

    return result;
}

static List<BsonDocument> CreateSensorReadings(List<BsonDocument> warehouses, DateTime baseDate, Random random)
{
    var result = new List<BsonDocument>();
    for (var i = 0; i < 96; i++)
    {
        var warehouse = warehouses[i % warehouses.Count];
        result.Add(new BsonDocument
        {
            ["_id"] = $"SEN-{i + 1:0000}",
            ["deviceId"] = $"DEV-{warehouse["_id"].AsString}-{i % 6:00}",
            ["warehouseId"] = warehouse["_id"],
            ["capturedAt"] = baseDate.AddMinutes(i * 15),
            ["temperature"] = Math.Round(18 + random.NextDouble() * 12, 2),
            ["humidity"] = Math.Round(35 + random.NextDouble() * 45, 2),
            ["battery"] = Math.Round(0.35 + random.NextDouble() * 0.65, 3),
            ["status"] = i % 17 == 0 ? "warning" : "ok",
            ["samples"] = new BsonArray
            {
                Math.Round(18 + random.NextDouble() * 12, 2),
                Math.Round(18 + random.NextDouble() * 12, 2),
                Math.Round(18 + random.NextDouble() * 12, 2),
            },
            ["location"] = warehouse["address"].AsDocument["geo"],
        });
    }

    return result;
}

static List<BsonDocument> CreateAuditLogs(List<BsonDocument> customers, List<BsonDocument> orders, List<BsonDocument> products, DateTime baseDate, Random random)
{
    var entities = new[] { "customer", "order", "product", "payment", "warehouse" };
    var actions = new[] { "create", "update", "approve", "cancel", "sync" };
    var result = new List<BsonDocument>();
    for (var i = 0; i < 160; i++)
    {
        var entity = entities[i % entities.Length];
        BsonValue entityId = entity switch
        {
            "customer" => customers[i % customers.Count]["_id"],
            "order" => orders[i % orders.Count]["_id"],
            "product" => products[i % products.Count]["_id"],
            _ => new BsonValue($"REF-{i:0000}"),
        };
        result.Add(new BsonDocument
        {
            ["_id"] = ObjectId.NewObjectId(),
            ["entity"] = entity,
            ["entityId"] = entityId,
            ["action"] = actions[i % actions.Length],
            ["createdAt"] = baseDate.AddMinutes(-5000 + i * 31),
            ["actor"] = i % 8 == 0 ? "system" : $"user{i % 12}",
            ["severity"] = i % 19 == 0 ? 3 : i % 7 == 0 ? 2 : 1,
            ["ip"] = $"10.20.{i % 16}.{20 + i % 200}",
            ["metadata"] = new BsonDocument
            {
                ["requestId"] = Guid.NewGuid(),
                ["elapsedMs"] = random.Next(12, 900),
                ["diff"] = new BsonDocument
                {
                    ["before"] = i % 2 == 0 ? "old" : BsonValue.Null,
                    ["after"] = "new",
                },
            },
        });
    }

    return result;
}

static List<BsonDocument> CreateFieldTypes(DateTime baseDate)
{
    return new List<BsonDocument>
    {
        new()
        {
            ["_id"] = "types-001",
            ["label"] = "all-types",
            ["int32Value"] = 123,
            ["int64Value"] = 9876543210L,
            ["doubleValue"] = 123.456,
            ["decimalValue"] = 99999.1234m,
            ["boolValue"] = true,
            ["dateTimeValue"] = baseDate,
            ["guidValue"] = Guid.Parse("11111111-2222-3333-4444-555555555555"),
            ["objectIdValue"] = ObjectId.NewObjectId(),
            ["binaryValue"] = new byte[] { 1, 2, 3, 4, 250 },
            ["nullValue"] = BsonValue.Null,
            ["arrayValue"] = new BsonArray { 1, "two", false, BsonValue.Null },
            ["documentValue"] = new BsonDocument { ["nested"] = true, ["level"] = 2 },
            ["unicodeValue"] = "中文值测试：多字节字符 / emoji-free",
        },
        new()
        {
            ["_id"] = "types-002",
            ["label"] = "sparse-row",
            ["int32Value"] = -12,
            ["boolValue"] = false,
            ["dateTimeValue"] = baseDate.AddDays(1),
            ["documentValue"] = new BsonDocument { ["onlyHere"] = "sparse" },
        },
        new()
        {
            ["_id"] = "types-003",
            ["label"] = "json-heavy",
            ["arrayValue"] = new BsonArray
            {
                new BsonDocument { ["k"] = "a", ["v"] = 1 },
                new BsonDocument { ["k"] = "b", ["v"] = 2 },
            },
            ["documentValue"] = new BsonDocument
            {
                ["matrix"] = new BsonArray { new BsonArray { 1, 2 }, new BsonArray { 3, 4 } },
                ["notes"] = "nested array/document should display as JSON text in JDBC",
            },
        },
    };
}

static List<BsonDocument> CreateAppSettings(DateTime baseDate)
{
    return new List<BsonDocument>
    {
        new() { ["_id"] = "setting:feature.new-checkout", ["key"] = "feature.new-checkout", ["value"] = true, ["type"] = "boolean", ["updatedAt"] = baseDate },
        new() { ["_id"] = "setting:limits.max-order-lines", ["key"] = "limits.max-order-lines", ["value"] = 200, ["type"] = "integer", ["updatedAt"] = baseDate.AddHours(1) },
        new() { ["_id"] = "setting:pricing.default-tax", ["key"] = "pricing.default-tax", ["value"] = 0.13m, ["type"] = "decimal", ["updatedAt"] = baseDate.AddHours(2) },
        new() { ["_id"] = "setting:ui.theme", ["key"] = "ui.theme", ["value"] = "light", ["type"] = "string", ["updatedAt"] = baseDate.AddHours(3) },
        new() { ["_id"] = "setting:notice.zh", ["key"] = "notice.zh", ["value"] = "测试数据库，请勿用于生产", ["type"] = "string", ["updatedAt"] = baseDate.AddHours(4) },
        new() { ["_id"] = "setting:integrations", ["key"] = "integrations", ["value"] = new BsonDocument { ["erp"] = true, ["wms"] = true, ["crm"] = false }, ["type"] = "document", ["updatedAt"] = baseDate.AddHours(5) },
        new() { ["_id"] = "setting:allowed-currencies", ["key"] = "allowed-currencies", ["value"] = new BsonArray { "CNY", "USD", "EUR" }, ["type"] = "array", ["updatedAt"] = baseDate.AddHours(6) },
        new() { ["_id"] = "setting:null-sample", ["key"] = "null-sample", ["value"] = BsonValue.Null, ["type"] = "null", ["updatedAt"] = baseDate.AddHours(7) },
    };
}
