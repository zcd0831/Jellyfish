package io.github.zcd0831.jellyfish.infrastructure.model;

/** 凭据只从进程环境读，永不落盘（05-action §5.4）。 */
interface CredentialLookup {

    String lookup(String envName);
}
