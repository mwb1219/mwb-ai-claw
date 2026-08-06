package com.mwb.ai.claw.api;

import com.alibaba.cola.dto.MultiResponse;
import com.alibaba.cola.dto.Response;
import com.mwb.ai.claw.dto.CustomerAddCmd;
import com.mwb.ai.claw.dto.CustomerListByNameQry;
import com.mwb.ai.claw.dto.data.CustomerDTO;

public interface CustomerServiceI {

    Response addCustomer(CustomerAddCmd customerAddCmd);

    MultiResponse<CustomerDTO> listByName(CustomerListByNameQry customerListByNameQry);
}
