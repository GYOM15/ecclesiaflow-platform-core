package com.ecclesiaflow.platform.contract;

/**
 * The module's contract is missing, unreadable or declares something the platform cannot enforce.
 * Thrown at startup only: a module whose contract cannot be read does not start.
 */
public class InvalidApiContractException extends IllegalStateException {

    private InvalidApiContractException(String message) {
        super(message);
    }

    private InvalidApiContractException(String message, Throwable cause) {
        super(message, cause);
    }

    public static InvalidApiContractException noLocation() {
        return new InvalidApiContractException("No OpenAPI contract location is configured: set "
                + ApiContractProperties.PREFIX + ".locations");
    }

    public static InvalidApiContractException missing(String location) {
        return new InvalidApiContractException("No OpenAPI contract found at " + location + ": the module must ship "
                + "its contract on the classpath, or point " + ApiContractProperties.PREFIX + ".locations at it");
    }

    public static InvalidApiContractException unreadable(String source, Throwable cause) {
        return new InvalidApiContractException("The OpenAPI contract " + source + " cannot be read", cause);
    }

    public static InvalidApiContractException malformed(String source, String detail) {
        return new InvalidApiContractException("The OpenAPI contract " + source + " is malformed: " + detail);
    }

    public static InvalidApiContractException duplicateOperation(String method, String template,
                                                                 String firstSource, String secondSource) {
        return new InvalidApiContractException("The operation " + method + " " + template + " is declared twice ("
                + firstSource + ", " + secondSource + "): a request must resolve to exactly one operation");
    }

    public static InvalidApiContractException invalidExtension(ContractOperation operation, String extension,
                                                               Object value, String expected) {
        return new InvalidApiContractException("The operation " + operation.label() + " (" + operation.source()
                + ") declares " + extension + ": " + value + "; expected " + expected);
    }
}
