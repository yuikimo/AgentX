import type { InstallFieldDefinition, InstallFieldsConfig } from "@/types/tool";

export const INSTALL_FIELDS_REFERENCE_TEMPLATE: InstallFieldsConfig = {
  fields: [
    {
      name: "username",
      label: "用户名",
      type: "string",
      required: true,
      placeholder: "例如：your_username",
    },
  ],
};

export function parseJsonObject(value: unknown): Record<string, any> | null {
  if (!value) return null;
  if (typeof value === "object" && !Array.isArray(value)) return value as Record<string, any>;
  if (typeof value !== "string") return null;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function formatJson(value: unknown): string {
  if (value == null || value === "") return "";
  const parsed = parseJsonObject(value);
  if (parsed) return JSON.stringify(parsed, null, 2);
  return typeof value === "string" ? value : JSON.stringify(value, null, 2);
}

export function getInstallFields(config?: InstallFieldsConfig | null): InstallFieldDefinition[] {
  return Array.isArray(config?.fields) ? config.fields : [];
}

export function hasInstallFields(config?: InstallFieldsConfig | null): boolean {
  return getInstallFields(config).length > 0;
}

export function isSecretInstallField(field?: InstallFieldDefinition | null): boolean {
  if (!field) return false;
  if (field.sensitive === true) return true;
  const type = String(field.type || "").toLowerCase();
  return type === "secret" || type === "password";
}

export function buildDefaultTemplateAndFields(installCommand: unknown): {
  installTemplate: Record<string, any>;
  installFields: InstallFieldsConfig;
} {
  const installTemplate = parseJsonObject(installCommand) || {};

  return {
    installTemplate,
    installFields: INSTALL_FIELDS_REFERENCE_TEMPLATE,
  };
}
