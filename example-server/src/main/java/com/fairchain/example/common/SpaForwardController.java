package com.fairchain.example.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * React 화면(브라우저 라우팅)용 — /concert/3, /admin 같은 주소로 바로 들어와도 index.html을 준다.
 * /api/** 와 확장자가 있는 정적 파일(/assets/x.js 등)은 제외 — 모든 구간에 점(.)이 없을 때만 넘긴다.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/{a:(?!api$)[^.]*}",
            "/{a:(?!api$|assets$)[^.]*}/{b:[^.]*}",
            "/{a:(?!api$|assets$)[^.]*}/{b:[^.]*}/{c:[^.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
