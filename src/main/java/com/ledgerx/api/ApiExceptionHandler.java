package com.ledgerx.api;

import com.ledgerx.transfer.TransferException;
import com.ledgerx.wallet.UnsupportedCurrencyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TransferException.WalletNotFound.class)
    ProblemDetail notFound(TransferException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TransferException.InsufficientFunds.class)
    ProblemDetail insufficientFunds(TransferException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler({TransferException.Invalid.class, UnsupportedCurrencyException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
