package dev.qb2.inference.provider;

public interface LanguageModel {
  String complete(String context) throws Exception;
}
