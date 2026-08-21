package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * 更新会话命令（当前支持修改标题）
 */
@Data
public class UpdateSessionCmd {

    /** 会话标题 */
    private String title;
}
