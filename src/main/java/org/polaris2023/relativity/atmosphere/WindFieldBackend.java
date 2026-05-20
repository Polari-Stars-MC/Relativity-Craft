package org.polaris2023.relativity.atmosphere;

public interface WindFieldBackend {
    AtmosphereSample sample(double x, double y, double z, long worldTime);
}
