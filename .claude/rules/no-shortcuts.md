# No Shortcuts Policy

## Forbidden Patterns

### 1. Implicit Waits
```java
// FORBIDDEN
driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
```
Use `WebDriverWait` with explicit `ExpectedConditions` only.

### 2. Thread.sleep
```java
// FORBIDDEN in production code
Thread.sleep(2000);
```
Use `WaitStrategy` methods. Only allowed in tests for async side effects.

### 3. Bare element access without wait
```java
// FORBIDDEN
driver.findElement(By.id("foo")).click();
```
Always go through `WaitStrategy.waitForClickable()` or equivalent.

### 4. Swallowed exceptions
```java
// FORBIDDEN
try { ... } catch (Exception e) { /* ignore */ }
```
Either rethrow as `AutoQAException` or log at ERROR and rethrow.

### 5. Magic strings for locators
```java
// FORBIDDEN
driver.findElement(By.xpath("//div[@class='btn']"));
```
Locators come from `RecordedEvent.element` or `LocatorResolver`. Never hardcode.

### 6. Skipping PopupSentinel
Every action in `PlayerEngine` must call `PopupSentinel.check(driver)` first.
No exceptions.

### 7. Committing credentials
No API keys, passwords, or tokens in any committed file. Use env vars referenced
in `config.properties`.
