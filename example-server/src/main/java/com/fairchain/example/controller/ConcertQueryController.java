package com.fairchain.example.controller;

import com.fairchain.example.dto.ConcertDetailDto;
import com.fairchain.example.dto.ConcertListDto;
import com.fairchain.example.dto.ConcertSearchCondition;
import com.fairchain.example.service.ConcertQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C-0705 콘서트 조회 (SS-07) */
@RestController
public class ConcertQueryController {

    private final ConcertQueryService concertQueryService;

    public ConcertQueryController(ConcertQueryService concertQueryService) {
        this.concertQueryService = concertQueryService;
    }

    /** GET /concerts?keyword=&venue=&from=&to= (날짜는 yyyy-MM-dd) */
    @GetMapping("/concerts")
    public List<ConcertListDto> list(@ModelAttribute ConcertSearchCondition condition) {
        return concertQueryService.getConcertList(condition);
    }

    @GetMapping("/concerts/{concertId}")
    public ConcertDetailDto detail(@PathVariable("concertId") Long concertId) {
        return concertQueryService.getConcertDetail(concertId);
    }
}
