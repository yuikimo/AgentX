import React from "react";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import FileUpload from "@/components/ui/file-upload";

interface AgentFormData {
  name: string;
  avatar: string | null;
  description: string;
}

interface AgentBasicInfoFormProps {
  formData: AgentFormData;
  selectedType: "chat" | "agent";
  updateFormField: (field: string, value: any) => void;
}

const AgentBasicInfoForm: React.FC<AgentBasicInfoFormProps> = ({
  formData,
  selectedType,
  updateFormField,
}) => {
  return (
    <div className="space-y-6">
      {/* 名称和头像 */}
      <div>
        <h2 className="text-lg font-medium mb-4">名称 & 头像</h2>
        <div className="flex gap-4 items-start">
          <div className="flex-1">
            <Label htmlFor="agent-name" className="mb-2 block">
              名称
            </Label>
            <Input
              id="agent-name"
              placeholder={`给你的${selectedType === "chat" ? "聊天助理" : "功能性助理"}起个名字`}
              value={formData.name}
              onChange={(e) => updateFormField("name", e.target.value)}
              className="mb-2"
            />
          </div>
          <div>
            <Label className="mb-2 block">头像</Label>
            <FileUpload
              variant="avatar"
              size="lg"
              value={formData.avatar}
              onChange={(url) => updateFormField("avatar", url)}
              placeholder={
                <div className="text-blue-600">
                  {formData.name ? formData.name.charAt(0).toUpperCase() : "🤖"}
                </div>
              }
              uploadText="上传头像"
              changeText="更换头像"
              removeText="移除头像"
              maxSize={2 * 1024 * 1024} // 2MB
            />
          </div>
        </div>
      </div>

      {/* 描述 */}
      <div>
        <h2 className="text-lg font-medium mb-2">描述</h2>
        <Textarea
          placeholder={`输入${selectedType === "chat" ? "聊天助理" : "功能性助理"}的描述`}
          value={formData.description}
          onChange={(e) => updateFormField("description", e.target.value)}
          rows={4}
        />
      </div>
    </div>
  );
};

export default AgentBasicInfoForm; 
