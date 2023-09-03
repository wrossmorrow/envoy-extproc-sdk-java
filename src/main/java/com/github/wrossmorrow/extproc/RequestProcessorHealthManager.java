package com.github.wrossmorrow.extproc;

public interface RequestProcessorHealthManager {
  public void serving();

  public void notServing();

  public void failed();
}
