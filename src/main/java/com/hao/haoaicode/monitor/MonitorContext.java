package com.hao.haoaicode.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class MonitorContext implements Serializable {

    private String appId;
    private String userId;

    @Serial
    public static final long serialVersionUID = 1l;

}
