package service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优化的短链接生成服务
 * <p>
 * 1. 使用双重检查锁保证线程安全
 * 2. TODO: 添加 LRU 缓存淘汰机制
 * 3. 支持自定义短码长度
 * 4. 增加错误处理和边界检查
 * 5. 添加碰撞检测和重试机制
 * 6. 性能优化（减少对象创建）
 */
public class ShortLinkService {
    // Base62 字符集（优化为不可变数组）
    private static final char[] CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int BASE = CHARSET.length;

    // 默认短码长度（可配置）
    private static final int DEFAULT_KEY_LENGTH = 7;
    // 最大重试次数（防止哈希碰撞）
    private static final int MAX_COLLISION_RETRY = 3;

    private static final String DEFAULT_DOMAIN_PREFIX = "https://louiscool/";

    // 使用双重锁机制保证线程安全
    private final Object lock = new Object();
    // 自增ID生成器（使用AtomicLong保证原子性）
    private final AtomicLong counter = new AtomicLong(1);

    // 存储映射（使用ConcurrentHashMap保证线程安全）
    private final Map<String, String> keyToUrl = new ConcurrentHashMap<>();
    private final Map<String, String> urlToKey = new ConcurrentHashMap<>();

    // 添加缓存淘汰机制（最大缓存条目）
    private static final int MAX_CACHE_SIZE = 100_000;

    // 域名前缀（可配置）
    private final String domainPrefix;

    /**
     * 构造函数（使用默认域名前缀）
     */
    public ShortLinkService() {
        this(DEFAULT_DOMAIN_PREFIX);
    }

    /**
     * 构造函数（可自定义域名前缀）
     *
     * @param domainPrefix 域名前缀（如 "<a href="https://short.url/">...</a>"）
     */
    public ShortLinkService(String domainPrefix) {
        if (domainPrefix == null || domainPrefix.isEmpty()) {
            throw new IllegalArgumentException("Domain prefix cannot be null or empty");
        }
        this.domainPrefix = domainPrefix.endsWith("/") ? domainPrefix : domainPrefix + "/";
    }

    /**
     * 生成短链接（带缓存检查）
     *
     * @param originalUrl 原始URL
     * @return 完整的短链接
     */
    public String getShortLink(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) {
            throw new IllegalArgumentException("Original URL cannot be null or empty");
        }

        // 检查缓存中是否已存在
        String existingKey = urlToKey.get(originalUrl);
        if (existingKey != null) {
            return domainPrefix + existingKey;
        }

        // 生成短码（带碰撞检测）
        String key = generateUniqueKey(originalUrl);
        return domainPrefix + key;
    }

    /**
     * 生成唯一短码（带碰撞检测和重试）
     */
    private String generateUniqueKey(String originalUrl) {
        // 双重检查锁减少同步块竞争
        synchronized (lock) {
            // 再次检查（防止其他线程已生成）
            String existingKey = urlToKey.get(originalUrl);
            if (existingKey != null) {
                return existingKey;
            }

            // 生成短码（最多重试MAX_COLLISION_RETRY次）
            String key = null;
            for (int i = 0; i < MAX_COLLISION_RETRY; i++) {
                key = encode(generateId());

                // 检查键是否已存在（极小概率事件）
                if (!keyToUrl.containsKey(key)) {
                    break;
                }

                // 碰撞时重新生成
                key = null;
            }

            if (key == null) {
                // 碰撞重试失败，使用更安全的生成方式
                key = encodeWithRandomExtension();
            }

            // 添加缓存淘汰机制
            if (keyToUrl.size() >= MAX_CACHE_SIZE) {
                evictOldestEntries();
            }

            // 存储映射
            keyToUrl.put(key, originalUrl);
            urlToKey.put(originalUrl, key);

            return key;
        }
    }

    /**
     * 生成唯一ID（组合自增ID和随机数）
     */
    private long generateId() {
        long timestamp = System.currentTimeMillis() % 1_000_000; // 取后6位时间戳
        long counterPart = counter.getAndIncrement() % 10_000;  // 取后4位计数器
        long randomPart = ThreadLocalRandom.current().nextInt(1000); // 3位随机数

        // 组合：6位时间戳 + 4位计数器 + 3位随机数 = 13位数字
        return timestamp * 10_000_000L + counterPart * 1000L + randomPart;
    }

    /**
     * 带随机扩展的编码（用于解决哈希碰撞）
     */
    private String encodeWithRandomExtension() {
        String baseKey = encode(counter.getAndIncrement());
        int randomExt = ThreadLocalRandom.current().nextInt(1000); // 3位随机扩展
        return baseKey + CHARSET[randomExt % BASE];
    }

    /**
     * 缓存淘汰策略（淘汰最旧的10%条目）
     * 当前实现版本非真正的LRU（TODO）
     */
    private void evictOldestEntries() {
        int evictCount = MAX_CACHE_SIZE / 10;
        keyToUrl.entrySet().stream()
                .limit(evictCount)
                .forEach(entry -> {
                    String key = entry.getKey();
                    String url = entry.getValue();
                    keyToUrl.remove(key);
                    urlToKey.remove(url);
                });
    }

    /**
     * 从短链接恢复原始URL
     *
     * @param shortLink 完整短链接或短码
     * @return 原始URL或null
     */
    public String restore(String shortLink) {
        if (shortLink == null || shortLink.isEmpty()) {
            return null;
        }

        // 提取短码（处理完整URL格式）
        String key = extractKey(shortLink);
        return keyToUrl.get(key);
    }

    /**
     * 从完整短链接中提取短码
     */
    private String extractKey(String shortLink) {
        if (shortLink.startsWith(domainPrefix)) {
            return shortLink.substring(domainPrefix.length());
        }
        return shortLink;
    }

    /**
     * 优化后的Base62编码（指定长度）
     */
    private String encode(long num) {
//        System.out.println("before encode: " + num);
        StringBuilder sb = new StringBuilder(DEFAULT_KEY_LENGTH);
        while (num > 0 && sb.length() < DEFAULT_KEY_LENGTH) {
            int idx = (int) (num % BASE);
            sb.append(CHARSET[idx]);
            num /= BASE;
        }

        // 填充到固定长度
        while (sb.length() < DEFAULT_KEY_LENGTH) {
            sb.append(CHARSET[0]);
        }

        return sb.reverse().toString();
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        ShortLinkService service = new ShortLinkService();

        // 测试相同URL
        String url1 = "https://www.example.com/long/path";
        String key1 = service.getShortLink(url1);
        String key2 = service.getShortLink(url1);
        System.out.println("key1: " + key1 + "   key2: " + key2);
        System.out.println("Same URL should have same key: " + key1.equals(key2));

        // 测试不同URL
        String key3 = service.getShortLink("https://another.example.com/");
        System.out.println("Different URLs have different keys: " + !key1.equals(key3));

        // 测试恢复功能
        System.out.println("Restore result: " + url1.equals(service.restore(key1)));

        // 测试边界值
        try {
            service.getShortLink("");
        } catch (IllegalArgumentException e) {
            System.out.println("Empty URL test passed");
        }

        // 性能测试
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100_000; i++) {
            service.getShortLink("https://test.url/" + i);
        }
        System.out.println("Generated 100,000 links in " + (System.currentTimeMillis() - start) + "ms");
    }
}
