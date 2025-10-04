package com.hao.haoaicode.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
public class HtmlCodeResult {

    @Description("HTML 代码")
    private String htmlCode;
    @Description("生成代码的描述")
    private String description;
}
