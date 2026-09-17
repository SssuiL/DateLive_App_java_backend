CREATE TABLE live_gift_catalog_items (
 id varchar(64) PRIMARY KEY, code varchar(32) NOT NULL UNIQUE CHECK(code ~ '^[a-z0-9_]{2,32}$'),
 name varchar(64) NOT NULL, price_coins bigint NOT NULL CHECK(price_coins BETWEEN 1 AND 1000000),
 icon_url varchar(500), animation_url varchar(500), renderer_type varchar(16) NOT NULL DEFAULT 'static' CHECK(renderer_type IN ('static','lottie','alpha_video')),
 animation_checksum varchar(64), animation_file_size bigint, min_client_version varchar(32),
 presentation_tier varchar(16) NOT NULL DEFAULT 'compact' CHECK(presentation_tier IN ('compact','spotlight','fullscreen')),
 animation_duration_ms integer NOT NULL DEFAULT 900 CHECK(animation_duration_ms BETWEEN 300 AND 10000),
 resource_version integer NOT NULL DEFAULT 1, sort_order integer NOT NULL DEFAULT 0 CHECK(sort_order BETWEEN 0 AND 100000),
 status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','inactive')),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE live_gift_assets (
 id varchar(64) PRIMARY KEY,gift_id varchar(64) NOT NULL REFERENCES live_gift_catalog_items(id),
 asset_type varchar(16) NOT NULL CHECK(asset_type IN ('icon','animation')),content_type varchar(64) NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 5242880),checksum varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO live_gift_catalog_items(id,code,name,price_coins,sort_order,presentation_tier,animation_duration_ms)
SELECT 'gift_catalog_'||code,code,name,price,sort,
 CASE WHEN price>=520 THEN 'fullscreen' WHEN price>=52 THEN 'spotlight' ELSE 'compact' END,
 CASE WHEN price>=520 THEN 2600 WHEN price>=52 THEN 1500 ELSE 900 END
FROM (VALUES ('rose','玫瑰',1,10),('candy','糖果',6,20),('coffee','暖心咖啡',10,30),('heart','心动',20,40),
 ('star_bottle','星光瓶',52,50),('meteor','流星雨',100,60),('bouquet','告白花束',188,70),('diamond','闪耀钻石',520,80),
 ('firework','盛典烟花',999,90),('rocket','星河火箭',1314,100),('crown','星耀皇冠',2999,110),('galaxy_promise','银河之约',5200,120)) AS gifts(code,name,price,sort);
