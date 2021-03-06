package com.example.fullstacktemplate.dto;


import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class TokenAccessRequestDto {

    @NotBlank(message = "token.blank")
    private String token;

}
