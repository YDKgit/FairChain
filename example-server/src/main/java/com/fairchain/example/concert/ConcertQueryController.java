package com.fairchain.example.concert;

import com.fairchain.example.concert.ConcertDtos.ConcertDetailDto;
import com.fairchain.example.concert.ConcertDtos.ConcertListDto;
import com.fairchain.example.concert.ConcertDtos.ConcertSearchCondition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/concerts")
public class ConcertQueryController {

    private final ConcertQueryService concertQueryService;

    public ConcertQueryController(ConcertQueryService concertQueryService) {
        this.concertQueryService = concertQueryService;
    }

    @GetMapping
    public List<ConcertListDto> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String venue) {
        return concertQueryService.getConcertList(new ConcertSearchCondition(keyword, venue));
    }

    @GetMapping("/{concertId}")
    public ConcertDetailDto detail(@PathVariable Long concertId) {
        return concertQueryService.getConcertDetail(concertId);
    }
}
