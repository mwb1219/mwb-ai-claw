---
name: unit-test-writing
description: Java 单元测试编写规范：JUnit + Mockito，AAA 模式、边界与异常测试、覆盖率取舍。当用户要求写测试、单元测试、测试用例、mock 依赖时使用。
---

# Java 单元测试编写

## When to Use

用户要求为 Java 代码编写单元测试、补测试用例，或评估测试覆盖。

## 规范

- 框架：JUnit（项目测试依赖为准）+ Mockito mock 外部依赖；断言优先使用 AssertJ
- 命名：`方法名_场景_预期结果`，如 `createOrder_库存不足_抛BizException`
- 结构：AAA（Arrange 准备 → Act 执行 → Assert 断言），必要时加注释分隔
- 每个测试只测一个行为点，避免一个用例内串多段逻辑

## 必测维度

1. **正常路径**：主流程返回正确结果
2. **边界值**：null / 空集合 / 最大最小值 / 超长输入
3. **异常路径**：参数非法、依赖调用失败、权限不足 → 断言异常类型与 message
4. **交互验证**：Mockito verify 确认依赖被正确调用（次数、参数）

## 输出格式

```java
@Test
void createOrder_库存不足_抛BizException() {
    // Arrange
    when(stockGateway.query(any())).thenReturn(0);
    // Act & Assert
    assertThatThrownBy(() -> service.createOrder(cmd))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("库存不足");
}
```

## 要点

- 优先 mock 外部边界（DB/HTTP/LLM/文件），领域纯逻辑用真实对象
- 测试命名即文档，可读性优先
- 写完可用 shell 运行 `mvn -pl <模块> test -Dtest=<类名>` 验证通过
